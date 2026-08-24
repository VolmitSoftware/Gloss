package art.arcane.gloss.tab;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public final class TablistService implements Listener {
    private static final String PLAYER_TOKEN = "$player";
    private static final String GROUP_TOKEN = "$group";
    private static final int ANIMATION_REFRESH_INTERVAL_TICKS = 1;
    private static final int VIEWER_DEPENDENT = TextPipeline.HAS_PLACEHOLDER | TextPipeline.HAS_FUNCTION;
    private static final long HEADER_FOOTER_HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final int HEADER_FOOTER_HEARTBEAT_LIMIT_PER_CYCLE = 64;
    static final int APPLY_FAST = 1;
    static final int APPLY_FULL = 2;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<TablistDoc> registry;
    private final Map<UUID, TabOverride> overrides;
    private final Map<UUID, String> appliedListNames;
    private final Map<UUID, AppliedHeaderFooter> appliedHeaderFooters;
    private final Map<UUID, ListNameSource> listNameSources;
    private final Map<UUID, PlayerApplyQueue> playerApplyQueues;
    private final Set<UUID> fastOverridePlayers;
    private final Set<UUID> fastNamePlayers;
    private final Set<UUID> fastPlayers;
    private final FastDriverLifecycle fastDriverLifecycle;
    private final AtomicLong docGeneration;
    private final AtomicLong driverEpoch;
    private volatile TablistDoc activeDoc;
    private volatile int driverTaskId;
    private volatile int driverIntervalTicks;
    private volatile HeaderFooterMemo headerFooterMemo;
    private volatile boolean running;

    public TablistService(Gloss plugin) {
        this.plugin = plugin;
        this.defaults = new ShippedDefaults(TablistDoc.KIND, plugin.getDataFolder(),
            ShippedDocumentCatalog.TABLIST.names());
        this.registry = DocumentRegistry.singleFile(TablistDoc.KIND,
            new File(plugin.getDataFolder(), TablistDoc.KIND + ".json"), TablistDoc::parse, TablistDoc::revision);
        this.overrides = new ConcurrentHashMap<>();
        this.appliedListNames = new ConcurrentHashMap<>();
        this.appliedHeaderFooters = new ConcurrentHashMap<>();
        this.listNameSources = new ConcurrentHashMap<>();
        this.playerApplyQueues = new ConcurrentHashMap<>();
        this.fastOverridePlayers = ConcurrentHashMap.newKeySet();
        this.fastNamePlayers = ConcurrentHashMap.newKeySet();
        this.fastPlayers = ConcurrentHashMap.newKeySet();
        this.fastDriverLifecycle = new FastDriverLifecycle();
        this.docGeneration = new AtomicLong();
        this.driverEpoch = new AtomicLong();
        this.activeDoc = TablistDoc.DEFAULTS;
        this.driverTaskId = -1;
        this.driverIntervalTicks = -1;
    }

    /** Single-pass splice of the {@code $player} and {@code $group} tokens; substituted text is never rescanned. */
    public static String substituteTokens(String raw, String playerName, String groupName) {
        if (raw == null) {
            return "";
        }
        int cursor = raw.indexOf('$');
        if (cursor < 0) {
            return raw;
        }

        String player = playerName == null ? "" : playerName;
        String group = groupName == null ? "" : groupName;
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int copied = 0;
        while (cursor >= 0) {
            if (raw.startsWith(PLAYER_TOKEN, cursor)) {
                out.append(raw, copied, cursor).append(player);
                copied = cursor + PLAYER_TOKEN.length();
            } else if (raw.startsWith(GROUP_TOKEN, cursor)) {
                out.append(raw, copied, cursor).append(group);
                copied = cursor + GROUP_TOKEN.length();
            }
            cursor = raw.indexOf('$', Math.max(copied, cursor + 1));
        }
        out.append(raw, copied, raw.length());
        return out.toString();
    }

    public static ListNameChoice chooseListName(boolean op, String primaryGroup, Map<String, String> nameFormats) {
        if (op && nameFormats.containsKey(TablistDoc.OP_GROUP_KEY)) {
            return new ListNameChoice(nameFormats.get(TablistDoc.OP_GROUP_KEY), TablistDoc.OP_GROUP_KEY);
        }
        if (primaryGroup != null && !primaryGroup.isBlank()) {
            String groupKey = primaryGroup.trim().toLowerCase(Locale.ROOT);
            if (nameFormats.containsKey(groupKey)) {
                return new ListNameChoice(nameFormats.get(groupKey), primaryGroup);
            }
        }
        if (nameFormats.containsKey(TablistDoc.DEFAULT_GROUP_KEY)) {
            return new ListNameChoice(nameFormats.get(TablistDoc.DEFAULT_GROUP_KEY),
                primaryGroup == null ? "" : primaryGroup);
        }
        return new ListNameChoice(TablistDoc.FALLBACK_FORMAT, primaryGroup == null ? "" : primaryGroup);
    }

    /**
     * The shipped {@code tablist.json} is written only while the feature is on; with tablist off
     * {@link #doc()} runs on {@link TablistDoc#DEFAULTS} and nothing is materialised.
     */
    public void enable() {
        if (plugin.cfg().tablist().enabled()) {
            defaults.extractMissing();
        }
        registry.reload();
        activeDoc = committedDoc();
        docGeneration.incrementAndGet();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.watchdog().register(TablistDoc.KIND, this::pollRegistry);
        startDriver();
    }

    public void disable() {
        plugin.watchdog().unregister(TablistDoc.KIND);
        registry.close();
        HandlerList.unregisterAll(this);
        synchronized (fastDriverLifecycle) {
            stopDriverLocked();
            overrides.clear();
            fastOverridePlayers.clear();
            fastNamePlayers.clear();
            fastPlayers.clear();
        }
        for (PlayerApplyQueue queue : playerApplyQueues.values()) {
            queue.retire();
        }
        playerApplyQueues.clear();
        resetAppliedHeaderFooters();
        resetAppliedListNames();
    }

    public void reload() {
        stopDriver();
        if (plugin.cfg().tablist().enabled()) {
            defaults.extractMissing();
        }
        registry.reload();
        activeDoc = committedDoc();
        docGeneration.incrementAndGet();
        clearFastNamePlayers();
        startDriver();
        if (!plugin.cfg().tablist().enabled()) {
            resetAppliedHeaderFooters();
            resetAppliedListNames();
        } else {
            TablistDoc doc = doc();
            if (!doc.useHeaderFooter()) {
                resetAppliedHeaderFooters();
            }
            if (!doc.groupListNames()) {
                resetAppliedListNames();
            }
        }
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    /** Players Gloss currently manages: an applied list name, header/footer, or plugin override. */
    public int managedPlayerCount() {
        if (overrides.isEmpty()) {
            if (appliedHeaderFooters.isEmpty()) {
                return appliedListNames.size();
            }
            if (appliedListNames.isEmpty()) {
                return appliedHeaderFooters.size();
            }
        }
        Set<UUID> managed = new HashSet<>(appliedListNames.keySet());
        managed.addAll(appliedHeaderFooters.keySet());
        managed.addAll(overrides.keySet());
        return managed.size();
    }

    public void setTab(Player player, String header, String footer) {
        UUID uuid = player.getUniqueId();
        TabOverride override = new TabOverride(header == null ? "" : header, footer == null ? "" : footer);
        synchronized (fastDriverLifecycle) {
            overrides.put(uuid, override);
            if (requiresFastRefresh(override)) {
                fastOverridePlayers.add(uuid);
            } else {
                fastOverridePlayers.remove(uuid);
            }
            refreshFastPlayerLocked(uuid);
            reconcileFastDriverLocked();
        }
        pushNow(player);
    }

    public void resetTab(Player player) {
        UUID uuid = player.getUniqueId();
        synchronized (fastDriverLifecycle) {
            overrides.remove(uuid);
            fastOverridePlayers.remove(uuid);
            refreshFastPlayerLocked(uuid);
            reconcileFastDriverLocked();
        }
        pushNow(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        synchronized (fastDriverLifecycle) {
            overrides.remove(uuid);
            fastOverridePlayers.remove(uuid);
            fastNamePlayers.remove(uuid);
            fastPlayers.remove(uuid);
            reconcileFastDriverLocked();
        }
        appliedListNames.remove(uuid);
        appliedHeaderFooters.remove(uuid);
        listNameSources.remove(uuid);
        PlayerApplyQueue queue = playerApplyQueues.remove(uuid);
        if (queue != null) {
            queue.retire();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerJoinEvent event) {
        invalidateAndPush(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerRespawnEvent event) {
        invalidateAndPush(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerChangedWorldEvent event) {
        invalidateAndPush(event.getPlayer());
    }

    private TablistDoc doc() {
        return activeDoc;
    }

    private TablistDoc committedDoc() {
        GlossDocument<TablistDoc> document = registry.get(TablistDoc.KIND);
        return document == null ? TablistDoc.DEFAULTS : document.value();
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applyDelta(delta))) {
            Gloss.warnThrottled("tablist-hotload-scheduling",
                "Tablist hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyDelta(DocumentDelta delta) {
        GlossDocument<TablistDoc> document = registry.get(delta, TablistDoc.KIND);
        TablistDoc updated = document == null ? TablistDoc.DEFAULTS : document.value();
        activeDoc = updated;
        docGeneration.incrementAndGet();
        clearFastNamePlayers();
        reconcileDriverInterval();
        if (updated.useHeaderFooter()) {
            appliedHeaderFooters.clear();
        } else {
            resetAppliedHeaderFooters();
        }
        if (updated.groupListNames()) {
            appliedListNames.clear();
            listNameSources.clear();
        } else {
            resetAppliedListNames();
        }
    }

    private void startDriver() {
        synchronized (fastDriverLifecycle) {
            startDriverLocked();
        }
    }

    private void startDriverLocked() {
        if (!plugin.cfg().tablist().enabled()) {
            return;
        }
        if (driverTaskId != -1) {
            reconcileFastDriverLocked();
            return;
        }
        driverEpoch.incrementAndGet();
        running = true;
        int intervalTicks = desiredDriverIntervalTicks();
        driverTaskId = plugin.scheduler().sr(this::tick, intervalTicks);
        driverIntervalTicks = intervalTicks;
        reconcileFastDriverLocked();
    }

    private void stopDriver() {
        synchronized (fastDriverLifecycle) {
            stopDriverLocked();
        }
    }

    private void stopDriverLocked() {
        running = false;
        driverEpoch.incrementAndGet();
        if (driverTaskId != -1) {
            plugin.scheduler().csr(driverTaskId);
            driverTaskId = -1;
        }
        fastDriverLifecycle.stop(plugin.scheduler()::csr);
        driverIntervalTicks = -1;
    }

    private void reconcileDriverInterval() {
        synchronized (fastDriverLifecycle) {
            if (!plugin.cfg().tablist().enabled()) {
                return;
            }
            int intervalTicks = desiredDriverIntervalTicks();
            if (driverTaskId != -1 && driverIntervalTicks == intervalTicks) {
                reconcileFastDriverLocked();
                return;
            }
            stopDriverLocked();
            startDriverLocked();
        }
    }

    private int desiredDriverIntervalTicks() {
        int configuredIntervalTicks = plugin.cfg().tablist().updateIntervalTicks();
        if (!plugin.cfg().text().functions()) {
            return configuredIntervalTicks;
        }
        return refreshIntervalTicks(doc(), configuredIntervalTicks);
    }

    private void reconcileFastDriver() {
        synchronized (fastDriverLifecycle) {
            reconcileFastDriverLocked();
        }
    }

    private void reconcileFastDriverLocked() {
        boolean required = running && fastDriverRequired(doc(), plugin.cfg().text().functions(),
            !fastOverridePlayers.isEmpty(), !fastNamePlayers.isEmpty(), driverIntervalTicks);
        fastDriverLifecycle.reconcile(required,
            () -> plugin.scheduler().sr(this::tickFastPlayers, ANIMATION_REFRESH_INTERVAL_TICKS),
            plugin.scheduler()::csr);
    }

    private void tick() {
        long epoch = driverEpoch.get();
        if (!isActiveEpoch(epoch)) {
            return;
        }
        HeaderFooterHeartbeatCycle heartbeatCycle =
            new HeaderFooterHeartbeatCycle(HEADER_FOOTER_HEARTBEAT_LIMIT_PER_CYCLE);
        for (Player player : Bukkit.getOnlinePlayers()) {
            requestApply(player, epoch, APPLY_FULL, heartbeatCycle);
        }
    }

    private void tickFastPlayers() {
        long epoch = driverEpoch.get();
        if (!isActiveEpoch(epoch)) {
            return;
        }
        for (UUID uuid : fastPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                synchronized (fastDriverLifecycle) {
                    fastOverridePlayers.remove(uuid);
                    fastNamePlayers.remove(uuid);
                    fastPlayers.remove(uuid);
                }
                continue;
            }
            requestApply(player, epoch, APPLY_FAST, null);
        }
        if (fastPlayers.isEmpty()) {
            reconcileFastDriver();
        }
    }

    private void pushNow(Player player) {
        long epoch = driverEpoch.get();
        if (!isActiveEpoch(epoch)) {
            return;
        }
        requestApply(player, epoch, APPLY_FULL, null);
    }

    private void requestApply(Player player, long epoch, int mode,
                              HeaderFooterHeartbeatCycle heartbeatCycle) {
        UUID uuid = player.getUniqueId();
        ApplyRequest request = new ApplyRequest(player, epoch, mode, heartbeatCycle);
        PlayerApplyQueue queue;
        while (true) {
            queue = playerApplyQueues.computeIfAbsent(uuid, ignored -> new PlayerApplyQueue());
            if (queue.offer(request)) {
                break;
            }
            if (!queue.isRetired()) {
                return;
            }
            playerApplyQueues.remove(uuid, queue);
        }
        PlayerApplyQueue scheduledQueue = queue;
        Runnable drain = () -> drainPlayerApplyQueue(uuid, scheduledQueue);
        AtomicBoolean retired = new AtomicBoolean();
        Runnable retirement = () -> {
            if (!retired.compareAndSet(false, true)) {
                return;
            }
            scheduledQueue.retire();
            playerApplyQueues.remove(uuid, scheduledQueue);
        };
        if (!FoliaScheduler.runEntity(plugin, player, drain, 0L, retirement)) {
            retirement.run();
        }
    }

    private void drainPlayerApplyQueue(UUID uuid, PlayerApplyQueue queue) {
        ApplyRequest request;
        while ((request = queue.next()) != null) {
            try {
                if (!isActiveEpoch(request.epoch())) {
                    continue;
                }
                if (request.mode() == APPLY_FULL) {
                    apply(request.player(), request.heartbeatCycle());
                } else {
                    applyFastPlayer(request.player());
                }
            } catch (Throwable failure) {
                Gloss.logExceptionStackThrottled(false, "tablist-player-refresh", failure,
                    "Tablist refresh failed for %s.", uuid);
            }
        }
    }

    private boolean isActiveEpoch(long epoch) {
        return running && epoch == driverEpoch.get() && plugin.cfg().tablist().enabled();
    }

    private void apply(Player player, HeaderFooterHeartbeatCycle heartbeatCycle) {
        if (!player.isOnline()) {
            return;
        }
        TablistDoc doc = doc();
        if (doc.useHeaderFooter()) {
            TabOverride override = overrides.get(player.getUniqueId());
            String header;
            String footer;
            if (override != null) {
                header = renderSafe(player, override.header());
                footer = renderSafe(player, override.footer());
            } else {
                HeaderFooterMemo memo = headerFooterMemo();
                header = memo.header() == null ? renderSafe(player, doc.header()) : memo.header();
                footer = memo.footer() == null ? renderSafe(player, doc.footer()) : memo.footer();
            }
            HeaderFooter rendered = new HeaderFooter(header, footer);
            UUID uuid = player.getUniqueId();
            AppliedHeaderFooter previous = appliedHeaderFooters.get(uuid);
            long nowNanos = System.nanoTime();
            if (shouldSendHeaderFooter(rendered, previous, nowNanos, heartbeatCycle)) {
                long nextHeartbeatNanos = nowNanos + HEADER_FOOTER_HEARTBEAT_NANOS;
                if (previous == null) {
                    nextHeartbeatNanos += initialHeartbeatOffsetNanos(uuid);
                }
                appliedHeaderFooters.put(uuid,
                    new AppliedHeaderFooter(rendered, nextHeartbeatNanos));
                player.setPlayerListHeaderFooter(header, footer);
            }
        }
        applyListName(player, doc);
    }

    private void applyFastOverride(Player player) {
        if (!player.isOnline() || !doc().useHeaderFooter()) {
            return;
        }
        TabOverride override = overrides.get(player.getUniqueId());
        if (override == null || !requiresFastRefresh(override)) {
            return;
        }
        String header = renderSafe(player, override.header());
        String footer = renderSafe(player, override.footer());
        HeaderFooter rendered = new HeaderFooter(header, footer);
        AppliedHeaderFooter previous = appliedHeaderFooters.get(player.getUniqueId());
        if (previous != null && rendered.equals(previous.content())) {
            return;
        }
        appliedHeaderFooters.put(player.getUniqueId(),
            new AppliedHeaderFooter(rendered,
                System.nanoTime() + HEADER_FOOTER_HEARTBEAT_NANOS));
        player.setPlayerListHeaderFooter(header, footer);
    }

    private void applyFastPlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        TablistDoc doc = doc();
        if (doc.useHeaderFooter() && fastOverridePlayers.contains(uuid)) {
            applyFastOverride(player);
        }
        if (doc.groupListNames() && fastNamePlayers.contains(uuid)) {
            applyListName(player, doc);
        }
    }

    private void applyListName(Player player, TablistDoc doc) {
        UUID uuid = player.getUniqueId();
        if (!doc.groupListNames()) {
            setFastNamePlayer(uuid, false);
            listNameSources.remove(uuid);
            if (appliedListNames.remove(uuid) != null) {
                player.setPlayerListName(null);
            }
            return;
        }
        String primaryGroup = plugin.groups().primaryGroupFor(player).orElse(null);
        ListNameChoice choice = chooseListName(player.isOp(), primaryGroup, doc.nameFormats());
        if (choice.template().isBlank()) {
            setFastNamePlayer(uuid, false);
            listNameSources.remove(uuid);
            if (appliedListNames.remove(uuid) != null) {
                player.setPlayerListName(null);
            }
            return;
        }
        String substituted = substituteTokens(choice.template(), player.getName(), choice.groupName());
        setFastNamePlayer(uuid, requiresFastNameRefresh(substituted, plugin.cfg().text().functions()));
        if ((TextPipeline.classify(substituted) & VIEWER_DEPENDENT) == 0) {
            // Viewer-independent: the render is a pure function of the substituted text plus the
            // emoji table, so an unchanged source guarantees an unchanged applied name.
            ListNameSource source = new ListNameSource(substituted, docGeneration.get(),
                TextPipeline.emojiGeneration());
            String applied = appliedListNames.get(uuid);
            if (source.equals(listNameSources.get(uuid)) && applied != null
                && !listNameNeedsApply(applied, applied, player.getPlayerListName())) {
                return;
            }
            listNameSources.put(uuid, source);
        } else {
            listNameSources.remove(uuid);
        }
        String rendered = renderSafe(player, substituted);
        String previous = appliedListNames.get(uuid);
        if (!listNameNeedsApply(rendered, previous, player.getPlayerListName())) {
            return;
        }
        appliedListNames.put(uuid, rendered);
        player.setPlayerListName(rendered);
    }

    /**
     * Document header/footer rendered once per document revision. A template carrying a placeholder
     * or a text function stays per-viewer and is reported as {@code null} here.
     */
    private HeaderFooterMemo headerFooterMemo() {
        long generation = docGeneration.get();
        long emojiGeneration = TextPipeline.emojiGeneration();
        HeaderFooterMemo current = headerFooterMemo;
        if (current != null && current.docGeneration() == generation && current.emojiGeneration() == emojiGeneration) {
            return current;
        }
        TablistDoc doc = doc();
        HeaderFooterMemo built = new HeaderFooterMemo(generation, emojiGeneration,
            staticRender(doc.header()), staticRender(doc.footer()));
        headerFooterMemo = built;
        return built;
    }

    private String staticRender(String raw) {
        String value = raw == null ? "" : raw;
        return (TextPipeline.classify(value) & VIEWER_DEPENDENT) == 0 ? renderSafe(null, value) : null;
    }

    private String renderSafe(Player player, String raw) {
        String rendered = plugin.text().render(player, raw == null ? "" : raw);
        return rendered == null ? "" : rendered;
    }

    private void resetAppliedHeaderFooters() {
        for (UUID uuid : appliedHeaderFooters.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            mutateOnEntityThread(player, () -> player.setPlayerListHeaderFooter("", ""));
        }
        appliedHeaderFooters.clear();
    }

    private void resetAppliedListNames() {
        for (UUID uuid : appliedListNames.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            mutateOnEntityThread(player, () -> player.setPlayerListName(null));
        }
        appliedListNames.clear();
        listNameSources.clear();
    }

    private void mutateOnEntityThread(Player player, Runnable action) {
        long epoch = driverEpoch.get();
        Runnable guardedAction = () -> {
            if (epoch == driverEpoch.get()) {
                action.run();
            }
        };
        if (plugin.scheduler().runEntity(player, guardedAction)) {
            return;
        }
        if (FoliaScheduler.isOwnedByCurrentRegion(player)) {
            guardedAction.run();
        }
    }

    private void invalidateAndPush(Player player) {
        UUID uuid = player.getUniqueId();
        appliedHeaderFooters.remove(uuid);
        appliedListNames.remove(uuid);
        listNameSources.remove(uuid);
        pushNow(player);
    }

    static boolean shouldSendHeaderFooter(HeaderFooter rendered, AppliedHeaderFooter previous,
                                          long nowNanos,
                                          HeaderFooterHeartbeatCycle heartbeatCycle) {
        if (previous == null || !rendered.equals(previous.content())) {
            return true;
        }
        return nowNanos - previous.nextHeartbeatNanos() >= 0L
            && heartbeatCycle != null
            && heartbeatCycle.tryAcquire();
    }

    static boolean listNameNeedsApply(String rendered, String memoized, String serverValue) {
        return !rendered.equals(memoized) || !rendered.equals(serverValue);
    }

    static long initialHeartbeatOffsetNanos(UUID uuid) {
        long folded = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        return Math.floorMod(folded, HEADER_FOOTER_HEARTBEAT_NANOS);
    }

    static int refreshIntervalTicks(TablistDoc doc, int configuredIntervalTicks) {
        if (usesAnimatedHeaderFooter(doc)) {
            return Math.min(configuredIntervalTicks, ANIMATION_REFRESH_INTERVAL_TICKS);
        }
        return configuredIntervalTicks;
    }

    static boolean fastDriverRequired(TablistDoc doc, boolean functionsEnabled,
                                      boolean hasFastOverrides, boolean hasFastNames,
                                      int driverIntervalTicks) {
        if (!functionsEnabled || driverIntervalTicks <= ANIMATION_REFRESH_INTERVAL_TICKS) {
            return false;
        }
        return (hasFastOverrides && doc.useHeaderFooter())
            || (hasFastNames && doc.groupListNames());
    }

    static boolean requiresFastNameRefresh(String substituted, boolean functionsEnabled) {
        return functionsEnabled && TextPipeline.requiresFastRefresh(substituted);
    }

    private static boolean usesAnimatedHeaderFooter(TablistDoc doc) {
        return doc.useHeaderFooter()
            && (TextPipeline.requiresFastRefresh(doc.header())
            || TextPipeline.requiresFastRefresh(doc.footer()));
    }

    private static boolean requiresFastRefresh(TabOverride override) {
        return TextPipeline.requiresFastRefresh(override.header())
            || TextPipeline.requiresFastRefresh(override.footer());
    }

    private void setFastNamePlayer(UUID uuid, boolean fast) {
        synchronized (fastDriverLifecycle) {
            boolean changed = fast ? fastNamePlayers.add(uuid) : fastNamePlayers.remove(uuid);
            if (!changed) {
                return;
            }
            refreshFastPlayerLocked(uuid);
            reconcileFastDriverLocked();
        }
    }

    private void clearFastNamePlayers() {
        synchronized (fastDriverLifecycle) {
            fastNamePlayers.clear();
            fastPlayers.clear();
            if (doc().useHeaderFooter()) {
                fastPlayers.addAll(fastOverridePlayers);
            }
            reconcileFastDriverLocked();
        }
    }

    private void refreshFastPlayerLocked(UUID uuid) {
        TablistDoc doc = doc();
        if ((doc.useHeaderFooter() && fastOverridePlayers.contains(uuid))
            || (doc.groupListNames() && fastNamePlayers.contains(uuid))) {
            fastPlayers.add(uuid);
        } else {
            fastPlayers.remove(uuid);
        }
    }

    public record ListNameChoice(String template, String groupName) {
    }

    private record TabOverride(String header, String footer) {
    }

    record HeaderFooter(String header, String footer) {
    }

    record AppliedHeaderFooter(HeaderFooter content, long nextHeartbeatNanos) {
    }

    private record ListNameSource(String substituted, long docGeneration, long emojiGeneration) {
    }

    private record HeaderFooterMemo(long docGeneration, long emojiGeneration, String header, String footer) {
    }

    record ApplyRequest(Player player, long epoch, int mode,
                        HeaderFooterHeartbeatCycle heartbeatCycle) {
        ApplyRequest merge(ApplyRequest newer) {
            if (newer.epoch != epoch) {
                return newer.epoch > epoch ? newer : this;
            }
            if (newer.mode >= mode) {
                return newer;
            }
            return this;
        }
    }

    static final class PlayerApplyQueue {
        private ApplyRequest pending;
        private boolean scheduled;
        private boolean retired;

        synchronized boolean offer(ApplyRequest request) {
            if (retired) {
                return false;
            }
            pending = pending == null ? request : pending.merge(request);
            if (scheduled) {
                return false;
            }
            scheduled = true;
            return true;
        }

        synchronized ApplyRequest next() {
            if (pending != null) {
                ApplyRequest next = pending;
                pending = null;
                return next;
            }
            scheduled = false;
            return null;
        }

        synchronized void retire() {
            retired = true;
            pending = null;
            scheduled = false;
        }

        synchronized boolean isRetired() {
            return retired;
        }
    }

    static final class HeaderFooterHeartbeatCycle {
        private final AtomicInteger remaining;

        HeaderFooterHeartbeatCycle(int limit) {
            this.remaining = new AtomicInteger(Math.max(0, limit));
        }

        boolean tryAcquire() {
            int current = remaining.get();
            while (current > 0) {
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
                current = remaining.get();
            }
            return false;
        }

        int remaining() {
            return remaining.get();
        }
    }

    static final class FastDriverLifecycle {
        private int taskId = -1;
        private boolean required;

        synchronized void reconcile(boolean nextRequired, IntSupplier starter, IntConsumer canceller) {
            required = nextRequired;
            if (required && taskId == -1) {
                taskId = starter.getAsInt();
                return;
            }
            if (!required && taskId != -1) {
                int cancelledTaskId = taskId;
                taskId = -1;
                canceller.accept(cancelledTaskId);
            }
        }

        synchronized void stop(IntConsumer canceller) {
            required = false;
            if (taskId == -1) {
                return;
            }
            int cancelledTaskId = taskId;
            taskId = -1;
            canceller.accept(cancelledTaskId);
        }

        synchronized int taskId() {
            return taskId;
        }

        synchronized boolean required() {
            return required;
        }
    }
}
