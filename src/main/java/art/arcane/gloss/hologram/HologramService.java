package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.Hologram;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.volmlib.util.entity.StackExclusion;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class HologramService {
    private static final String DISPLAY_TAG = "gloss_display";
    private static final int NO_TASK = -1;

    private static final DocumentReviser<HologramDoc> REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(HologramDoc value) {
            return value.revision();
        }

        @Override
        public HologramDoc withRevision(HologramDoc value, long revision) {
            return value.withRevision(revision);
        }
    };

    private final Gloss plugin;
    private final NamespacedKey markerKey;
    private final DocumentRegistry<HologramDoc> registry;
    private final DocumentStore<HologramDoc> store;
    private final Map<String, PersistentHologram> holograms;
    private final Set<TemporaryHologramDisplay> temporaries;
    private final Map<UUID, TextDisplay> leased;
    private final ExecutorService fileExecutor;
    private final HologramListener listener;
    private int driverTaskId;
    private int temporaryTaskId;
    private volatile HologramTick lastTick;

    public HologramService(Gloss plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "hologram");
        File folder = new File(plugin.getDataFolder(), HologramDoc.KIND);
        this.store = new DocumentStore<>(HologramDoc.KIND, folder, REVISER);
        this.registry = DocumentRegistry.folder(HologramDoc.KIND, folder, HologramDoc::parse,
            HologramDoc::revision, store::isOwnWrite);
        this.holograms = new ConcurrentHashMap<>();
        this.temporaries = ConcurrentHashMap.newKeySet();
        this.leased = new ConcurrentHashMap<>();
        this.fileExecutor = Executors.newSingleThreadExecutor(HologramService::createFileThread);
        this.listener = new HologramListener();
        this.driverTaskId = NO_TASK;
        this.temporaryTaskId = NO_TASK;
    }

    public void enable() {
        loadAll();
        plugin.watchdog().register("holograms", this::pollRegistry);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        sweepLoadedChunks();
        startTasks();
        if (!holograms.isEmpty()) {
            Gloss.info("Loaded " + holograms.size() + " holograms.");
        }
    }

    public void disable() {
        plugin.watchdog().unregister("holograms");
        stopTasks();
        HandlerList.unregisterAll(listener);
        for (PersistentHologram hologram : holograms.values()) {
            hologram.despawnAll();
        }

        for (TemporaryHologramDisplay temporary : temporaries) {
            temporary.destroy();
        }

        shutdownFileExecutor();
        temporaries.clear();
        holograms.clear();
        store.forgetAll();
        leased.clear();
    }

    public void reload() {
        stopTasks();
        for (PersistentHologram hologram : holograms.values()) {
            hologram.despawnAll();
        }

        holograms.clear();
        loadAll();
        startTasks();
    }

    public Hologram create(String id, Location location) {
        String safeId = requireSafeId(id);
        Objects.requireNonNull(location, "Hologram location may not be null.");
        PersistentHologram[] created = new PersistentHologram[1];
        PersistentHologram hologram = holograms.computeIfAbsent(safeId, key -> {
            created[0] = new PersistentHologram(this, key, location);
            return created[0];
        });
        if (hologram == created[0]) {
            persist(hologram);
        }

        return hologram;
    }

    public Hologram get(String id) {
        return id == null ? null : holograms.get(id);
    }

    public boolean has(String id) {
        return id != null && holograms.containsKey(id);
    }

    public void delete(String id) {
        String safeId = requireSafeId(id);
        PersistentHologram removed = holograms.remove(safeId);
        if (removed != null) {
            removed.despawnAll();
        }

        submitFileTask(() -> {
            try {
                store.delete(safeId);
            } catch (IOException failure) {
                Gloss.warn("Failed to delete hologram file " + safeId + ".json: " + failure.getMessage());
            }
        });
    }

    public List<Hologram> all() {
        return new ArrayList<Hologram>(holograms.values());
    }

    public TemporaryHologram createTemporary(String id, Location initial, long durationMs) {
        TemporaryHologramDisplay temporary = new TemporaryHologramDisplay(this, id, initial, durationMs);
        temporaries.add(temporary);
        return temporary;
    }

    public double stackSpread() {
        return plugin.cfg().holograms().stackDistance();
    }

    public int hologramCount() {
        return holograms.size();
    }

    public int temporaryCount() {
        return temporaries.size();
    }

    public int activeEntityCount() {
        return leased.size();
    }

    Gloss plugin() {
        return plugin;
    }

    HologramAnimator animator() {
        return plugin.animator();
    }

    double viewRange() {
        return plugin.cfg().holograms().viewRange();
    }

    boolean perViewerPlaceholders() {
        return plugin.cfg().holograms().perViewerPlaceholders();
    }

    boolean interpolatedMotion() {
        return plugin.cfg().holograms().interpolatedMotion();
    }

    int temporaryUpdateIntervalTicks() {
        return plugin.cfg().holograms().temporaryUpdateIntervalTicks();
    }

    boolean highFrequencyAnimations() {
        return plugin.cfg().holograms().highFrequencyAnimations();
    }

    boolean isActive(PersistentHologram hologram) {
        return holograms.get(hologram.id()) == hologram;
    }

    void removeTemporary(TemporaryHologramDisplay temporary) {
        temporaries.remove(temporary);
    }

    void configureDisplay(TextDisplay display) {
        display.setPersistent(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setViewRange(HologramMath.viewRangeMultiplier(plugin.cfg().holograms().viewRange()));
        display.setSeeThrough(false);
        display.setShadowed(false);
        display.addScoreboardTag(DISPLAY_TAG);
        StackExclusion.exclude(display);
        display.getPersistentDataContainer().set(markerKey, PersistentDataType.BOOLEAN, true);
        leased.put(display.getUniqueId(), display);
    }

    void despawnEntity(TextDisplay entity, Location anchor) {
        if (entity == null) {
            return;
        }

        leased.remove(entity.getUniqueId());
        Runnable removal = () -> {
            if (entity.isValid()) {
                entity.remove();
            }
        };
        boolean folia = plugin.scheduler().isFoliaThreading();
        boolean ownsThread = folia ? FoliaScheduler.isOwnedByCurrentRegion(entity) : FoliaScheduler.isPrimaryThread();
        if (ownsThread) {
            removal.run();
            return;
        }
        if (plugin.isEnabled()) {
            if (plugin.scheduler().runEntity(entity, removal)) {
                return;
            }
            if (anchor != null && plugin.scheduler().runAt(anchor, removal)) {
                return;
            }
        }
        if (!folia) {
            removal.run();
        }
    }

    void persist(PersistentHologram hologram) {
        HologramDoc doc = hologram.toDoc(hologram.nextRevision());
        String id = hologram.id();
        submitFileTask(() -> {
            try {
                store.write(id, doc);
            } catch (IOException failure) {
                plugin.getLogger().log(Level.WARNING, "Failed to save hologram " + id, failure);
            }
        });
    }

    private void submitFileTask(Runnable task) {
        if (fileExecutor.isShutdown()) {
            task.run();
            return;
        }

        try {
            fileExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            task.run();
        }
    }

    private void shutdownFileExecutor() {
        fileExecutor.shutdown();
        try {
            if (!fileExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                Gloss.warn("Timed out waiting for hologram file writes to complete.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    String renderStaticLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }

            builder.append(plugin.text().renderStatic(lines.get(index)));
        }

        return builder.toString();
    }

    private void startTasks() {
        driverTaskId = plugin.scheduler().sr(this::driveHolograms, plugin.cfg().holograms().updateIntervalTicks());
        temporaryTaskId = plugin.scheduler().sr(this::driveTemporaries, plugin.cfg().holograms().temporaryUpdateIntervalTicks());
    }

    private void stopTasks() {
        if (driverTaskId != NO_TASK) {
            plugin.scheduler().csr(driverTaskId);
            driverTaskId = NO_TASK;
        }
        if (temporaryTaskId != NO_TASK) {
            plugin.scheduler().csr(temporaryTaskId);
            temporaryTaskId = NO_TASK;
        }
    }

    private void driveHolograms() {
        if (!plugin.cfg().holograms().enabled()) {
            for (PersistentHologram hologram : holograms.values()) {
                hologram.despawnAll();
            }

            sweepLeases();
            return;
        }

        HologramTick tick = new HologramTick();
        for (PersistentHologram hologram : holograms.values()) {
            hologram.update(tick);
        }

        publishTick(tick);
        sweepLeases();
    }

    private void driveTemporaries() {
        boolean enabled = plugin.cfg().holograms().enabled();
        HologramTick tick = new HologramTick();
        for (TemporaryHologramDisplay temporary : temporaries) {
            temporary.drive(tick, enabled);
        }

        publishTick(tick);
    }

    /**
     * Published only once the pass that filled it has finished. The map is lazily populated during
     * the pass, so handing it out earlier would let a reader on another region thread walk a map
     * that is still being written; a volatile write after the last mutation makes the snapshot
     * safely readable and permanently immutable in practice.
     */
    private void publishTick(HologramTick tick) {
        lastTick = tick;
    }

    /**
     * Runs {@code action} for every player the last drive pass saw within {@code rangeSquared} of
     * {@code anchor}, and reports whether that snapshot existed.
     *
     * <p>Callers that spawn a temporary at event rate would otherwise allocate a fresh
     * {@code world.getPlayers()} list per event purely to answer a proximity question the drive pass
     * already answered. Positions are at most one drive interval old. A false return means no pass
     * has captured this world yet and the caller must do its own scan.
     */
    public boolean forEachNearbyViewer(Location anchor, double rangeSquared, Consumer<Player> action) {
        HologramTick tick = lastTick;
        World world = anchor.getWorld();
        if (tick == null || world == null) {
            return false;
        }

        List<HologramTick.Viewer> viewers = tick.captured(world);
        if (viewers == null) {
            return false;
        }

        double anchorX = anchor.getX();
        double anchorY = anchor.getY();
        double anchorZ = anchor.getZ();
        for (HologramTick.Viewer viewer : viewers) {
            double dx = viewer.x() - anchorX;
            double dy = viewer.y() - anchorY;
            double dz = viewer.z() - anchorZ;
            if (dx * dx + dy * dy + dz * dz > rangeSquared) {
                continue;
            }

            action.accept(viewer.player());
        }

        return true;
    }

    private void sweepLeases() {
        if (leased.isEmpty()) {
            return;
        }

        leased.values().removeIf(display -> !display.isValid());
    }

    void prunePlayer(UUID playerId) {
        for (TemporaryHologramDisplay temporary : temporaries) {
            temporary.onPlayerQuit(playerId);
        }

        for (PersistentHologram hologram : holograms.values()) {
            hologram.onPlayerQuit(playerId);
        }
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }

        if (SchedulerUtils.runGlobal(plugin, () -> applyDelta(delta))) {
            return;
        }

        Gloss.warn("Hologram hot reload could not reach the server thread; the change was skipped.");
    }

    /**
     * Spawns and despawns display entities, so it never runs on the watchdog IO thread. The poll
     * that produced the delta is pure file work; only this half needs the server context.
     */
    private void applyDelta(DocumentDelta delta) {
        for (String id : delta.loaded()) {
            GlossDocument<HologramDoc> document = registry.get(id);
            if (document == null) {
                continue;
            }

            applyDocument(id, document.value());
            Gloss.info("Hotloaded hologram " + id + ".json");
        }

        for (String id : delta.removed()) {
            PersistentHologram removed = holograms.remove(id);
            if (removed == null) {
                continue;
            }

            removed.despawnAll();
            Gloss.info("Hologram " + id + " removed from disk.");
        }
    }

    private void loadAll() {
        registry.reload();
        for (GlossDocument<HologramDoc> document : registry.snapshot().values()) {
            applyDocument(document.id(), document.value());
        }
    }

    private void applyDocument(String id, HologramDoc doc) {
        PersistentHologram existing = holograms.get(id);
        if (existing != null) {
            existing.apply(doc);
            return;
        }

        holograms.put(id, new PersistentHologram(this, id, doc));
    }

    private void sweepLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scheduleChunkPurge(world, chunk.getX(), chunk.getZ());
            }
        }
    }

    private void scheduleChunkPurge(World world, int chunkX, int chunkZ) {
        Location anchor = new Location(world, (chunkX << 4) + 8, world.getMinHeight(), (chunkZ << 4) + 8);
        plugin.scheduler().runAt(anchor, () -> purgeChunk(world, chunkX, chunkZ), 1);
    }

    private void purgeChunk(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            purgeOrphan(entity);
        }
    }

    private void purgeOrphan(Entity entity) {
        if (!(entity instanceof TextDisplay display)) {
            return;
        }
        if (leased.containsKey(display.getUniqueId()) || !isGlossDisplay(display)) {
            return;
        }

        display.remove();
    }

    private boolean isGlossDisplay(TextDisplay display) {
        return display.getScoreboardTags().contains(DISPLAY_TAG)
            || display.getPersistentDataContainer().has(markerKey, PersistentDataType.BOOLEAN);
    }

    private static Thread createFileThread(Runnable task) {
        Thread thread = new Thread(task, "Gloss Hologram IO");
        thread.setDaemon(true);
        return thread;
    }

    private static String requireSafeId(String id) {
        Objects.requireNonNull(id, "Hologram id may not be null.");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Hologram id may not be blank.");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("Hologram id may not contain path characters.");
        }

        return id;
    }

    private final class HologramListener implements Listener {
        @EventHandler
        public void onEntitiesLoad(EntitiesLoadEvent event) {
            for (Entity entity : event.getEntities()) {
                purgeOrphan(entity);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            prunePlayer(event.getPlayer().getUniqueId());
        }
    }
}
