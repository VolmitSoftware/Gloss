package art.arcane.gloss.leaderboard;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Samples every leaderboard's source on the owning player's region thread, persists what it read,
 * and republishes the ranked views every surface reads. A sweep starts every
 * {@code sampleIntervalTicks} and walks {@code playersPerTick} players a tick, so a thousand
 * players cost a handful of placeholder calls per tick rather than a thousand at once.
 */
public final class LeaderboardService implements GlossService {
    public static final String NAME = "leaderboards";
    private static final long MS_PER_TICK = 50L;
    private static final int NO_TASK = -1;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<LeaderboardDoc> registry;
    private final LeaderboardSnapshotStore store;
    private final LeaderboardSampleGuard guard = new LeaderboardSampleGuard();
    private final ConcurrentMap<String, LeaderboardSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Deque<UUID> sweep = new ArrayDeque<>();
    private final List<String> pipeFunctions = new ArrayList<>();
    private volatile Map<String, Loaded> boards = Map.of();
    private volatile Map<String, LeaderboardView> views = Map.of();
    private volatile boolean started;
    private long nextSweepMs;
    private int taskId = NO_TASK;

    public LeaderboardService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), LeaderboardDoc.KIND);
        this.defaults = new ShippedDefaults(LeaderboardDoc.KIND, folder,
            ShippedDocumentCatalog.LEADERBOARDS.names());
        this.registry = DocumentRegistry.folder(LeaderboardDoc.KIND, folder, LeaderboardDoc::parse,
            LeaderboardDoc::revision);
        this.store = new LeaderboardSnapshotStore(plugin.getDataFolder());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExprVariableNamespaces.global().register(new LeaderboardNamespace(this::view));
    }

    @Override
    public void enable() {
        if (!plugin.cfg().modules().leaderboards().enabled()) {
            return;
        }
        started = true;
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.snapshot());
        plugin.watchdog().register(LeaderboardDoc.KIND, this::poll);
        taskId = plugin.scheduler().sr(this::tick, 1);
    }

    @Override
    public void disable() {
        started = false;
        if (taskId != NO_TASK) {
            plugin.scheduler().csr(taskId);
            taskId = NO_TASK;
        }
        plugin.watchdog().unregister(LeaderboardDoc.KIND);
        persistAll();
        unregisterPipes();
        registry.close();
        guard.clear();
        sweep.clear();
        snapshots.clear();
        boards = Map.of();
        views = Map.of();
        ExprVariableNamespaces.global().unregister(LeaderboardNamespace.PREFIX);
    }

    @Override
    public void reload() {
        if (!started) {
            return;
        }
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.snapshot());
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return previous.modules().leaderboards().enabled() != next.modules().leaderboards().enabled()
            || previous.modules().leaderboards().maxEntries() != next.modules().leaderboards().maxEntries();
    }

    public LeaderboardView view(String id) {
        return views.get(id);
    }

    public List<String> ids() {
        List<String> ids = new ArrayList<>(boards.keySet());
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    public LeaderboardDoc document(String id) {
        Loaded loaded = boards.get(id);
        return loaded == null ? null : loaded.doc();
    }

    public List<String> resetToDefault(String nameOrStar) {
        List<String> restored = defaults.resetToDefault(nameOrStar);
        if (!restored.isEmpty()) {
            registry.reload();
            rebuild(registry.snapshot());
        }
        return restored;
    }

    /** Clears a board's period bucket, or everything it knows when {@code allTime} is asked for. */
    public boolean resetBoard(String id, boolean allTime) {
        Loaded loaded = boards.get(id);
        if (loaded == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        snapshots.put(id, allTime
            ? LeaderboardSnapshot.empty(id).withPeriodStart(
                LeaderboardRanking.periodStart(loaded.doc().reset(), now))
            : snapshots.getOrDefault(id, LeaderboardSnapshot.empty(id))
                .rolled(LeaderboardRanking.periodStart(loaded.doc().reset(), now)));
        persist(id);
        republish();
        return true;
    }

    /** Samples one board for every online player at once, for {@code /gloss leaderboard sample}. */
    public boolean sampleNow(String id) {
        Loaded loaded = boards.get(id);
        if (loaded == null) {
            return false;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            FoliaScheduler.runEntity(plugin, player, () -> sample(loaded, player));
        }
        republish();
        return true;
    }

    private void tick() {
        if (boards.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (sweep.isEmpty()) {
            startSweepIfDue(now);
            return;
        }
        int budget = playersPerTick();
        for (int taken = 0; taken < budget && !sweep.isEmpty(); taken++) {
            UUID id = sweep.poll();
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                FoliaScheduler.runEntity(plugin, player, () -> sampleAll(player));
            }
        }
        if (sweep.isEmpty()) {
            persistAll();
            republish();
        }
    }

    private void startSweepIfDue(long now) {
        if (now < nextSweepMs) {
            return;
        }
        nextSweepMs = now + plugin.cfg().modules().leaderboards().sampleIntervalTicks() * MS_PER_TICK;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sweep.add(player.getUniqueId());
        }
        rollDuePeriods(now);
    }

    private int playersPerTick() {
        int budget = 1;
        for (Loaded loaded : boards.values()) {
            budget = Math.max(budget, loaded.doc().sample().playersPerTick());
        }
        return budget;
    }

    private void sampleAll(Player player) {
        for (Loaded loaded : boards.values()) {
            sample(loaded, player);
        }
    }

    private void sample(Loaded loaded, Player player) {
        long now = System.currentTimeMillis();
        if (!guard.available(loaded.id(), now)) {
            return;
        }
        long started = System.nanoTime();
        Double value;
        try {
            value = loaded.source().sample(player);
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "leaderboard-source:" + loaded.id(), failure,
                "Leaderboard %s could not sample %s.", loaded.id(), player.getName());
            return;
        }
        if (guard.record(loaded.id(), System.nanoTime() - started, now)) {
            Gloss.warn("Leaderboard %s source is slow; paused for %d minutes.",
                loaded.id(), LeaderboardSampleGuard.QUARANTINE_MS / 60_000L);
        }
        if (value == null) {
            return;
        }
        snapshots.compute(loaded.id(), (id, current) ->
            (current == null ? store.load(id) : current)
                .sampled(player.getUniqueId(), player.getName(), value, now));
    }

    private void rollDuePeriods(long now) {
        for (Loaded loaded : boards.values()) {
            snapshots.computeIfPresent(loaded.id(), (id, snapshot) ->
                LeaderboardRanking.rollIfDue(snapshot, loaded.doc().reset(), now));
        }
    }

    private void republish() {
        long now = System.currentTimeMillis();
        Collection<UUID> online = onlineIds();
        Map<String, LeaderboardView> next = new LinkedHashMap<>(boards.size());
        for (Loaded loaded : boards.values()) {
            LeaderboardSnapshot snapshot = snapshots.computeIfAbsent(loaded.id(), store::load);
            next.put(loaded.id(), LeaderboardView.of(loaded.id(), loaded.doc(), snapshot, now, online,
                this::render));
        }
        views = Map.copyOf(next);
    }

    private String render(String template, String name, double value) {
        return plugin.text().renderScoped(null, template,
            new LeaderboardRowScope(plugin, name, value), UnaryOperator.identity());
    }

    private Collection<UUID> onlineIds() {
        List<UUID> online = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        return online;
    }

    private void persistAll() {
        for (String id : snapshots.keySet()) {
            persist(id);
        }
    }

    private void persist(String id) {
        LeaderboardSnapshot snapshot = snapshots.get(id);
        if (snapshot == null) {
            return;
        }
        try {
            store.save(snapshot);
        } catch (IOException failure) {
            Gloss.logExceptionStackThrottled(false, "leaderboard-save:" + id, failure,
                "Leaderboard %s snapshot could not be written.", id);
        }
    }

    private void poll() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> rebuild(registry.snapshot(delta)));
    }

    private void rebuild(Map<String, GlossDocument<LeaderboardDoc>> documents) {
        int maxEntries = plugin.cfg().modules().leaderboards().maxEntries();
        List<String> ids = new ArrayList<>(documents.keySet());
        ids.sort(String::compareTo);
        Map<String, Loaded> next = new LinkedHashMap<>(ids.size());
        for (String id : ids) {
            LeaderboardDoc doc = documents.get(id).value();
            try {
                next.put(id, new Loaded(id, doc, LeaderboardSource.of(doc),
                    Math.min(doc.size(), maxEntries)));
            } catch (IllegalArgumentException refused) {
                Gloss.warn("Leaderboard %s was not loaded: %s", id, refused.getMessage());
            }
        }
        boards = Map.copyOf(next);
        snapshots.keySet().retainAll(boards.keySet());
        registerPipes();
        republish();
        if (started) {
            Gloss.log(Level.INFO, "Leaderboards: %d loaded.", boards.size());
        }
    }

    /**
     * Pipe functions are registered once per document load, never per sample: they read the live
     * view, so a new sample changes what they render without touching the render generation.
     */
    private void registerPipes() {
        unregisterPipes();
        for (Loaded loaded : boards.values()) {
            String id = loaded.id();
            register(LeaderboardNamespace.PREFIX + "." + id + ".size", id, "size");
            register(LeaderboardNamespace.PREFIX + "." + id + ".resetAt", id, "resetAt");
            for (String field : List.of("rank", "value", "formatted")) {
                register(LeaderboardNamespace.PREFIX + "." + id + ".me." + field, id, "me." + field);
            }
            for (int rank = 1; rank <= loaded.size(); rank++) {
                for (String field : List.of("name", "value", "formatted", "uuid", "rank")) {
                    register(LeaderboardNamespace.PREFIX + "." + id + "." + rank + "." + field,
                        id, rank + "." + field);
                }
            }
        }
    }

    private void register(String function, String id, String suffix) {
        LeaderboardNamespace namespace = new LeaderboardNamespace(this::view);
        plugin.text().registerFunction(function, viewer -> {
            Object value = namespace.resolve(id + "." + suffix, ExprVariableContext.viewer(viewer));
            return value == null ? "" : String.valueOf(ExprFunctions.call("str", List.of(value)));
        });
        pipeFunctions.add(function);
    }

    private void unregisterPipes() {
        for (String function : pipeFunctions) {
            plugin.text().unregisterFunction(function);
        }
        pipeFunctions.clear();
    }

    private record Loaded(String id, LeaderboardDoc doc, LeaderboardSource source, int size) {
    }
}
