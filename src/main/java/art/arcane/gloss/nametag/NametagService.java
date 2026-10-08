package art.arcane.gloss.nametag;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.condition.RoleSnapshotStore;
import art.arcane.gloss.condition.EntityRoleSnapshotRuntime;
import art.arcane.gloss.condition.RolePositionIndex;
import art.arcane.gloss.condition.RoleSnapshotScope;
import art.arcane.gloss.condition.RoleSnapshotPendingException;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.util.common.TeamAllocator;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Owns {@code nametags/} documents and the pass that keeps every viewer's team prefixes in sync.
 * The quit hook also retires the shared team allocator, which every per-viewer team in Gloss comes
 * from, so it is registered even while the nametag feature itself is off.
 */
public final class NametagService implements GlossService, Listener {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<NametagDoc> registry;
    private final NametagDriver driver;
    private final RoleSnapshotStore snapshots;
    private final Map<UUID, Position> positions = new ConcurrentHashMap<>();
    private final AtomicReference<Object> pendingPass = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final Map<IdentityPair, Identity> pairIdentities = new ConcurrentHashMap<>();
    private final Set<IdentityPair> pendingPairs = ConcurrentHashMap.newKeySet();
    private volatile Set<String> subjectProperties = Set.of();
    private final BoundedConditionErrorCallback conditionErrors;
    private final ThreadLocal<Boolean> renderingIdentity = ThreadLocal.withInitial(() -> false);
    private final Map<UUID, Identity> identities = new ConcurrentHashMap<>();
    private final Set<UUID> pendingIdentities = ConcurrentHashMap.newKeySet();
    private volatile List<NametagRuntime> runtimes = List.of();
    private volatile boolean viewerDependent;
    private volatile long dependencyEmojiGeneration = -1L;
    private int taskId = -1;

    public NametagService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), NametagDoc.KIND);
        this.defaults = new ShippedDefaults(NametagDoc.KIND, folder, ShippedDocumentCatalog.NAMETAGS.names());
        this.registry = DocumentRegistry.folder(NametagDoc.KIND, folder, NametagDoc::parse, NametagDoc::revision);
        this.snapshots = new RoleSnapshotStore(new EntityRoleSnapshotRuntime(plugin, () -> plugin.isEnabled()),
            () -> plugin.cfg().modules().nametags().snapshotReadLimit());
        this.driver = new NametagDriver(plugin.teams(), this::render, this::distanceSquared);
        this.conditionErrors = BoundedConditionErrorCallback.bounded(100, error ->
            Gloss.logExceptionStackThrottled(false, "nametag-condition-" + error.path(), error.cause(),
                "Nametag condition %s failed and was treated as false.", error.path()));
    }

    @Override
    public String name() {
        return NametagDoc.KIND;
    }

    @Override
    public void enable() {
        boolean enabled = plugin.cfg().modules().nametags().enabled();
        if (enabled) {
            defaults.extractMissing();
        }
        loadAll();
        plugin.watchdog().register(NametagDoc.KIND, this::pollRegistry);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (!enabled) {
            return;
        }
        taskId = plugin.scheduler().sr(this::pass,
            Math.max(1, plugin.cfg().modules().nametags().refreshIntervalTicks()));
    }

    @Override
    public void disable() {
        generation.incrementAndGet();
        pendingPass.set(null);
        snapshots.clear();
        positions.clear();
        pairIdentities.clear();
        pendingPairs.clear();
        HandlerList.unregisterAll(this);
        plugin.watchdog().unregister(NametagDoc.KIND);
        if (taskId != -1) {
            plugin.scheduler().csr(taskId);
            taskId = -1;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            driver.releaseAll(viewer);
        }
        driver.clear();
        registry.close();
        runtimes = List.of();
        viewerDependent = false;
        identities.clear();
        pendingIdentities.clear();
    }

    @Override
    public void reload() {
        disable();
        enable();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().nametags().equals(next.modules().nametags());
    }

    public List<NametagRuntime> runtimes() {
        return runtimes;
    }

    public boolean viewerDependent() {
        long emoji = TextPipeline.emojiGeneration();
        if (dependencyEmojiGeneration != emoji) {
            List<NametagRuntime> active = runtimes;
            boolean personal = false;
            for (NametagRuntime runtime : active) {
                personal |= runtime.viewerDependent();
            }
            if (active == runtimes) {
                viewerDependent = personal;
                dependencyEmojiGeneration = emoji;
            }
        }
        return plugin.cfg().modules().nametags().enabled() && viewerDependent;
    }

    public String displayName(Player viewer, Player subject) {
        if (subject == null) {
            return "";
        }
        if (renderingIdentity.get() || !plugin.cfg().modules().nametags().enabled() || runtimes.isEmpty()) {
            return rawName(subject);
        }
        if (!viewerDependent()) {
            Identity cached = identities.get(subject.getUniqueId());
            if (cached != null && cached.runtimes() == runtimes && cached.expiresAt() > System.currentTimeMillis()) {
                return cached.name();
            }
            if (FoliaScheduler.isOwnedByCurrentRegion(subject)) {
                return refreshIdentity(subject);
            }
            requestIdentity(subject);
            return cached != null && cached.runtimes() == runtimes ? cached.name() : rawName(subject);
        }
        if (viewer == null) {
            return rawName(subject);
        }
        IdentityPair pair = new IdentityPair(viewer.getUniqueId(), subject.getUniqueId());
        Identity cached = pairIdentities.get(pair);
        if (cached != null && cached.runtimes() == runtimes && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.name();
        }
        if (FoliaScheduler.isOwnedByCurrentRegion(viewer)) {
            return refreshPair(viewer, subject, pair, cached);
        }
        requestPair(viewer, subject, pair);
        return cached != null && cached.runtimes() == runtimes ? cached.name() : rawName(subject);
    }

    private String rawName(Player subject) {
        if (FoliaScheduler.isOwnedByCurrentRegion(subject)) {
            return subject.getName();
        }
        return String.valueOf(snapshots.view(subject).variable("subject.name"));
    }

    static String formatName(String name, String color, String prefix, String suffix) {
        ChatColor nameColor;
        try {
            nameColor = ChatColor.valueOf(color.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            nameColor = ChatColor.WHITE;
        }
        return TextUtils.renderLegacy(prefix) + ChatColor.RESET + nameColor + name
            + TextUtils.renderLegacy(suffix) + ChatColor.RESET;
    }

    public int unsupportedSchemaCount() {
        return registry.unsupportedSchemaDocuments().size();
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    /** Re-applies every tag now, for {@code /gloss nametag refresh}. */
    public void refresh() {
        plugin.scheduler().s(this::pass);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerJoinEvent event) {
        generation.incrementAndGet();
        if (plugin.cfg().modules().nametags().enabled() && !viewerDependent()) {
            refreshIdentity(event.getPlayer());
        }
        plugin.scheduler().s(this::pass, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerChangedWorldEvent event) {
        generation.incrementAndGet();
        snapshots.forget(event.getPlayer().getUniqueId());
        positions.remove(event.getPlayer().getUniqueId());
        driver.releaseAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        generation.incrementAndGet();
        driver.forget(event.getPlayer().getUniqueId());
        UUID id = event.getPlayer().getUniqueId();
        identities.remove(id);
        pendingIdentities.remove(id);
        snapshots.forget(id);
        positions.remove(id);
        pairIdentities.keySet().removeIf(pair -> pair.viewer().equals(id) || pair.subject().equals(id));
        pendingPairs.removeIf(pair -> pair.viewer().equals(id) || pair.subject().equals(id));
    }

    private void loadAll() {
        registry.reload();
        rebuildRuntimes(registry.snapshot());
    }

    private void rebuildRuntimes(Map<String, GlossDocument<NametagDoc>> snapshot) {
        List<NametagRuntime> compiled = new ArrayList<>();
        for (GlossDocument<NametagDoc> document : snapshot.values()) {
            try {
                compiled.add(NametagRuntime.compile(document.id(), document.value()));
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "Nametag document \"%s\" failed to compile.",
                    document.id());
            }
        }
        compiled.sort(Comparator.comparing(NametagRuntime::id));
        boolean personal = false;
        Set<String> properties = new HashSet<>();
        for (NametagRuntime runtime : compiled) {
            personal |= runtime.viewerDependent();
            properties.addAll(runtime.subjectProperties());
        }
        generation.incrementAndGet();
        snapshots.clear();
        positions.clear();
        pairIdentities.clear();
        pendingPass.set(null);
        subjectProperties = Set.copyOf(properties);
        viewerDependent = personal;
        dependencyEmojiGeneration = TextPipeline.emojiGeneration();
        runtimes = List.copyOf(compiled);
        identities.clear();
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task), () -> applyDelta(delta))) {
            Gloss.warnThrottled("nametag-hotload-scheduling",
                "Nametag hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyDelta(DocumentDelta delta) {
        for (String id : delta.loaded()) {
            Gloss.log(Level.INFO, "Nametag document \"%s\" changed and was reloaded.", id);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            driver.releaseAll(viewer);
        }
        rebuildRuntimes(registry.snapshot(delta));
    }

    private void pass() {
        List<NametagRuntime> active = runtimes;
        Object pass = new Object();
        if (active.isEmpty() || !pendingPass.compareAndSet(null, pass)) {
            return;
        }
        long epoch = generation.get();
        GlossConfig.Nametags config = plugin.cfg().modules().nametags();
        driver.configure(config.viewerRange(), config.maxSubjectsPerViewer());
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            pendingPass.compareAndSet(pass, null);
            return;
        }
        boolean personal = active.stream().anyMatch(NametagRuntime::viewerDependent);
        Map<UUID, TeamAllocator.TeamStyle> shared = personal ? null : Collections.synchronizedMap(new HashMap<>());
        AtomicInteger remaining = new AtomicInteger(players.size());
        for (Player player : players) {
            AtomicBoolean completed = new AtomicBoolean();
            Runnable complete = () -> {
                if (completed.compareAndSet(false, true) && remaining.decrementAndGet() == 0) {
                    publishPass(players, active, shared, epoch, pass);
                }
            };
            if (!FoliaScheduler.runEntity(plugin, player, () -> {
                try {
                    if (epoch != generation.get() || !player.isOnline()) {
                        return;
                    }
                    snapshots.captureOnOwner(player, subjectProperties);
                    if (epoch != generation.get()) {
                        return;
                    }
                    Location location = player.getLocation();
                    if (location.getWorld() != null) {
                        positions.put(player.getUniqueId(), new Position(location.getWorld().getUID(),
                            location.getX(), location.getY(), location.getZ(), player.getName()));
                    }
                    if (shared != null) {
                        TeamAllocator.TeamStyle style = driver.prepare(player, player, active, this::liveScope, conditionErrors);
                        shared.put(player.getUniqueId(), style);
                        String name = style == null ? player.getName()
                            : formatName(player.getName(), style.color(), style.prefix(), style.suffix());
                        if (epoch == generation.get()) {
                            identities.put(player.getUniqueId(), new Identity(active, name, expiry()));
                        }
                    }
                } catch (RuntimeException failure) {
                    Gloss.logExceptionStackThrottled(false, "nametag-capture", failure,
                        "Could not capture nametag state for %s.", player.getUniqueId());
                } finally {
                    complete.run();
                }
            }, 0L, complete)) {
                complete.run();
            }
        }
    }

    private void publishPass(List<Player> players, List<NametagRuntime> active,
                             Map<UUID, TeamAllocator.TeamStyle> shared, long epoch, Object pass) {
        if (epoch != generation.get()) {
            pendingPass.compareAndSet(pass, null);
            return;
        }
        Map<UUID, String> names = new HashMap<>(players.size());
        List<RolePositionIndex.Point<Player>> points = new ArrayList<>(players.size());
        for (Player player : players) {
            Position position = positions.get(player.getUniqueId());
            if (position != null) {
                names.put(player.getUniqueId(), position.name());
                points.add(new RolePositionIndex.Point<>(player.getUniqueId(), position.world(),
                    position.x(), position.y(), position.z(), player));
            }
        }
        RolePositionIndex<Player> index = shared == null ? new RolePositionIndex<>(points) : null;
        GlossConfig.Nametags limits = plugin.cfg().modules().nametags();
        AtomicInteger remaining = new AtomicInteger(players.size());
        for (Player viewer : players) {
            AtomicBoolean completed = new AtomicBoolean();
            Runnable complete = () -> {
                if (completed.compareAndSet(false, true) && remaining.decrementAndGet() == 0) {
                    pendingPass.compareAndSet(pass, null);
                }
            };
            if (!FoliaScheduler.runEntity(plugin, viewer, () -> {
                try {
                    if (epoch == generation.get() && viewer.isOnline()) {
                        List<Player> selected = index == null ? players
                            : index.nearest(viewer.getUniqueId(), limits.viewerRange(), limits.maxSubjectsPerViewer(), viewer::canSee);
                        driver.applyViewer(viewer, new NametagDriver.ViewerPass(selected, active, shared, names, true),
                            this::scope, conditionErrors);
                    }
                } catch (RoleSnapshotPendingException pending) {
                    return;
                } catch (RuntimeException failure) {
                    Gloss.logExceptionStackThrottled(false, "nametag-apply", failure,
                        "Could not apply nametags for %s.", viewer.getUniqueId());
                } finally {
                    complete.run();
                }
            }, 0L, complete)) {
                complete.run();
            }
        }
    }

    private double distanceSquared(Player viewer, Player subject) {
        Position left = positions.get(viewer.getUniqueId());
        Position right = positions.get(subject.getUniqueId());
        if (left == null || right == null) {
            throw new RoleSnapshotPendingException();
        }
        if (!left.world().equals(right.world())) {
            return -1.0D;
        }
        double dx = left.x() - right.x();
        double dy = left.y() - right.y();
        double dz = left.z() - right.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private long expiry() {
        return System.currentTimeMillis() + plugin.cfg().modules().nametags().refreshIntervalTicks() * 50L;
    }

    private String refreshPair(Player viewer, Player subject, IdentityPair pair, Identity cached) {
        List<NametagRuntime> active = runtimes;
        long epoch = generation.get();
        try {
            String name = renderIdentity(viewer, subject);
            if (epoch == generation.get() && active == runtimes && viewer.isOnline()
                && Bukkit.getPlayer(subject.getUniqueId()) == subject) {
                pairIdentities.put(pair, new Identity(active, name, expiry()));
            }
            return name;
        } catch (RoleSnapshotPendingException pending) {
            return cached != null && cached.runtimes() == active ? cached.name() : rawName(subject);
        }
    }

    private void requestPair(Player viewer, Player subject, IdentityPair pair) {
        if (!pendingPairs.add(pair)) {
            return;
        }
        long epoch = generation.get();
        Runnable retired = () -> pendingPairs.remove(pair);
        if (!FoliaScheduler.runEntity(plugin, viewer, () -> {
            try {
                if (epoch == generation.get() && viewer.isOnline()) {
                    refreshPair(viewer, subject, pair, pairIdentities.get(pair));
                }
            } finally {
                retired.run();
            }
        }, 0L, retired)) {
            retired.run();
        }
    }

    public String displayNameFromScope(Player viewer, ExprScope scope) {
        String name = String.valueOf(scope.variable("subject.name"));
        if (renderingIdentity.get() || !plugin.cfg().modules().nametags().enabled() || runtimes.isEmpty()) {
            return name;
        }
        renderingIdentity.set(true);
        try {
            NametagRuntime runtime = NametagRuntime.pick(runtimes, scope, conditionErrors).orElse(null);
            if (runtime == null) {
                return name;
            }
            NametagDoc.Presentation presentation = runtime.profile(scope, conditionErrors).presentation();
            return formatName(name, presentation.color(),
                render(viewer, presentation.prefix(), scope), render(viewer, presentation.suffix(), scope));
        } finally {
            renderingIdentity.remove();
        }
    }

    private String renderIdentity(Player viewer, Player subject) {
        return displayNameFromScope(viewer, scope(viewer, subject));
    }

    private void requestIdentity(Player subject) {
        UUID id = subject.getUniqueId();
        if (!pendingIdentities.add(id)) {
            return;
        }
        long epoch = generation.get();
        if (!FoliaScheduler.runEntity(plugin, subject, () -> {
            try {
                if (epoch == generation.get() && subject.isOnline()) {
                    refreshIdentity(subject);
                }
            } finally {
                pendingIdentities.remove(id);
            }
        }, 0L, () -> pendingIdentities.remove(id))) {
            pendingIdentities.remove(id);
        }
    }

    private String refreshIdentity(Player subject) {
        List<NametagRuntime> active = runtimes;
        long epoch = generation.get();
        String name = renderIdentity(subject, subject);
        if (epoch == generation.get() && active == runtimes && subject.isOnline()) {
            identities.put(subject.getUniqueId(), new Identity(active, name, System.currentTimeMillis()
                + plugin.cfg().modules().nametags().refreshIntervalTicks() * 50L));
        }
        return name;
    }

    private String render(Player viewer, String raw, ExprScope scope) {
        boolean previous = renderingIdentity.get();
        renderingIdentity.set(true);
        try {
            String rendered = plugin.text().renderScoped(viewer, raw, scope, UnaryOperator.identity());
            return rendered == null ? "" : rendered;
        } finally {
            if (previous) {
                renderingIdentity.set(true);
            } else {
                renderingIdentity.remove();
            }
        }
    }

    private ExprScope scope(Player viewer, Player subject) {
        if (FoliaScheduler.isOwnedByCurrentRegion(subject)
            && (viewer == null || FoliaScheduler.isOwnedByCurrentRegion(viewer))) {
            return liveScope(viewer, subject);
        }
        ExprScope owned = new GlossConditionScope(plugin, GlossConditionContext.viewer(viewer));
        return new RoleSnapshotScope(owned, new ExprVariableContext(viewer, subject, null, null,
            Map.of("subject", snapshots.view(subject))));
    }

    private ExprScope liveScope(Player viewer, Player subject) {
        return new GlossConditionScope(plugin, new GlossConditionContext(viewer, subject, null, null, Map.of()));
    }


    private record Position(UUID world, double x, double y, double z, String name) {
    }

    private record IdentityPair(UUID viewer, UUID subject) {
    }

    private record Identity(List<NametagRuntime> runtimes, String name, long expiresAt) {
    }
}
