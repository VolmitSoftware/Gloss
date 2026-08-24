package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.animation.AnimationService;
import art.arcane.gloss.api.AnchoredHologram;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.text.TextPipeline;
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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class HologramService {
    private static final String DISPLAY_TAG = "gloss_display";
    private static final int NO_TASK = -1;
    private static final int ANIMATION_REFRESH_INTERVAL_TICKS = 1;
    private static final long LEASE_SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int VIEWER_RECONCILE_BATCH = 32;
    private static final int STARTUP_PURGE_BATCH = 32;

    private enum FileMutationKind {
        WRITE,
        DELETE
    }

    private record FileMutation(String id, FileMutationKind kind, HologramDoc doc) {
    }

    private record ChunkPurge(World world, int chunkX, int chunkZ) {
    }

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
    private final HologramViewerIndex viewerIndex;
    private final Set<PersistentHologram> persistentTicks;
    private final Map<UUID, ViewerWorkQueue> viewerWorkQueues;
    private final Map<String, FileMutation> pendingFileMutations;
    private final Queue<ChunkPurge> startupChunkPurges;
    private final ExecutorService fileExecutor;
    private final HologramListener listener;
    private final AtomicBoolean driverReconcileQueued;
    private final AtomicBoolean fileDrainQueued;
    private final AtomicLong viewerWorkDispatches;
    private final AtomicLong fileDrainSubmissions;
    private int driverTaskId;
    private int fastDriverTaskId;
    private int driverIntervalTicks;
    private int temporaryTaskId;
    private int viewerReconcileTaskId;
    private int startupPurgeTaskId;
    private volatile boolean driverRunning;
    private volatile long nextLeaseSweepNanos;

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
        this.viewerIndex = new HologramViewerIndex();
        this.persistentTicks = ConcurrentHashMap.newKeySet();
        this.viewerWorkQueues = new ConcurrentHashMap<>();
        this.pendingFileMutations = new ConcurrentHashMap<>();
        this.startupChunkPurges = new ConcurrentLinkedQueue<>();
        this.fileExecutor = Executors.newSingleThreadExecutor(HologramService::createFileThread);
        this.listener = new HologramListener();
        this.driverReconcileQueued = new AtomicBoolean();
        this.fileDrainQueued = new AtomicBoolean();
        this.viewerWorkDispatches = new AtomicLong();
        this.fileDrainSubmissions = new AtomicLong();
        this.driverTaskId = NO_TASK;
        this.fastDriverTaskId = NO_TASK;
        this.driverIntervalTicks = NO_TASK;
        this.temporaryTaskId = NO_TASK;
        this.viewerReconcileTaskId = NO_TASK;
        this.startupPurgeTaskId = NO_TASK;
    }

    public void enable() {
        loadAll();
        plugin.watchdog().register("holograms", this::pollRegistry);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        captureOnlineViewers();
        sweepLoadedChunks();
        startTasks();
        if (!holograms.isEmpty()) {
            Gloss.info("Loaded " + holograms.size() + " holograms.");
        }
    }

    public void disable() {
        plugin.watchdog().unregister("holograms");
        registry.close();
        stopTasks();
        HandlerList.unregisterAll(listener);
        List<PersistentHologram> retiring = new ArrayList<>(holograms.values());
        holograms.clear();
        retirePersistentHolograms(retiring, "disable");
        retireTemporaryHolograms(new ArrayList<>(temporaries), "disable");
        flushFileMutations();
        shutdownFileExecutor();
        temporaries.clear();
        persistentTicks.clear();
        store.forgetAll();
        leased.clear();
        viewerIndex.clear();
        viewerWorkQueues.clear();
    }

    public void reload() {
        stopTasks();
        List<PersistentHologram> retiring = new ArrayList<>(holograms.values());
        holograms.clear();
        retirePersistentHolograms(retiring, "reload");
        flushFileMutations();
        loadAll();
        startTasks();
    }

    public AnchoredHologram create(String id, Location location) {
        String safeId = requireSafeId(id);
        Objects.requireNonNull(location, "Hologram location may not be null.");
        PersistentHologram[] created = new PersistentHologram[1];
        PersistentHologram hologram = holograms.computeIfAbsent(safeId, key -> {
            created[0] = new PersistentHologram(this, key, location);
            return created[0];
        });
        if (hologram == created[0]) {
            persist(hologram);
            requestDriverIntervalReconcile();
        }

        return hologram;
    }

    public AnchoredHologram get(String id) {
        return id == null ? null : holograms.get(id);
    }

    public boolean has(String id) {
        return id != null && holograms.containsKey(id);
    }

    public void delete(String id) {
        String safeId = requireSafeId(id);
        PersistentHologram[] removedHolder = new PersistentHologram[1];
        holograms.compute(safeId, (key, current) -> {
            removedHolder[0] = current;
            pendingFileMutations.put(key, new FileMutation(key, FileMutationKind.DELETE, null));
            return null;
        });
        scheduleFileDrain();
        PersistentHologram removed = removedHolder[0];
        if (removed != null) {
            removed.despawnAll();
            requestDriverIntervalReconcile();
        }
    }

    public List<AnchoredHologram> all() {
        return new ArrayList<AnchoredHologram>(holograms.values());
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

    int persistentUpdateIntervalTicks() {
        return plugin.cfg().holograms().updateIntervalTicks();
    }

    boolean highFrequencyAnimations() {
        return plugin.cfg().holograms().highFrequencyAnimations();
    }

    long animationGeneration() {
        AnimationService animations = plugin.animations();
        return animations == null ? 0L : animations.generation();
    }

    boolean animationFramesViewerSpecific(String raw) {
        AnimationService animations = plugin.animations();
        return animations != null && animations.framesViewerSpecific(raw);
    }

    boolean hasDynamicAnimationContent(List<String> lines) {
        AnimationService animations = plugin.animations();
        return animations == null || animations.hasDynamicAnimationContent(lines);
    }

    boolean hasFastDynamicAnimationContent(List<String> lines) {
        AnimationService animations = plugin.animations();
        return animations != null && animations.hasFastDynamicAnimationContent(lines);
    }

    boolean isActive(PersistentHologram hologram) {
        return holograms.get(hologram.id()) == hologram;
    }

    void removeTemporary(TemporaryHologramDisplay temporary) {
        temporaries.remove(temporary);
    }

    void configureDisplay(TextDisplay display, boolean seeThrough, Display.Billboard billboard) {
        display.setPersistent(false);
        display.setBillboard(billboard);
        display.setViewRange(HologramMath.viewRangeMultiplier(plugin.cfg().holograms().viewRange()));
        display.setSeeThrough(seeThrough);
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

    void runEntity(Entity entity, Runnable action, Runnable retired) {
        Runnable retirement = once(retired);
        if (!FoliaScheduler.runEntity(plugin, entity, action, 0L, retirement)) {
            retirement.run();
        }
    }

    void runViewerWork(Player player, UUID playerId, String key, Runnable work) {
        runViewerWork(player, playerId, key, work, 0L);
    }

    void runViewerWork(Player player, UUID playerId, String key, Runnable work, long delayTicks) {
        if (delayTicks > 0L) {
            FoliaScheduler.runEntity(plugin, player,
                () -> runViewerWork(player, playerId, key, work), delayTicks,
                () -> prunePlayer(playerId));
            return;
        }
        ViewerWorkQueue queue = viewerWorkQueues.computeIfAbsent(playerId,
            ignored -> new ViewerWorkQueue());
        queue.tasks.put(key, work);
        if (!queue.scheduled.compareAndSet(false, true)) {
            return;
        }
        viewerWorkDispatches.incrementAndGet();
        Runnable drain = () -> drainViewerWork(playerId, queue);
        Runnable retirement = once(() -> retireViewerWork(playerId, queue));
        if (!FoliaScheduler.runEntity(plugin, player, drain, 0L, retirement)) {
            retirement.run();
        }
    }

    long viewerWorkDispatchCount() {
        return viewerWorkDispatches.get();
    }

    private void drainViewerWork(UUID playerId, ViewerWorkQueue queue) {
        while (true) {
            List<Map.Entry<String, Runnable>> batch = new ArrayList<>(queue.tasks.entrySet());
            for (Map.Entry<String, Runnable> entry : batch) {
                if (!queue.tasks.remove(entry.getKey(), entry.getValue())) {
                    continue;
                }
                try {
                    entry.getValue().run();
                } catch (Throwable failure) {
                    Gloss.logExceptionStackThrottled(false, "hologram-viewer-refresh", failure,
                        "Hologram viewer refresh failed for %s.", playerId);
                }
            }
            queue.scheduled.set(false);
            if (queue.tasks.isEmpty() || !queue.scheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void retireViewerWork(UUID playerId, ViewerWorkQueue queue) {
        queue.tasks.clear();
        queue.scheduled.set(false);
        viewerWorkQueues.remove(playerId, queue);
    }

    void persist(PersistentHologram hologram) {
        if (!isActive(hologram)) {
            return;
        }
        HologramDoc doc = hologram.toDoc(hologram.nextRevision());
        String id = hologram.id();
        FileMutation mutation = new FileMutation(id, FileMutationKind.WRITE, doc);
        pendingFileMutations.compute(id, (key, current) -> {
            if (!isActive(hologram)) {
                return current;
            }
            return mutation;
        });
        if (pendingFileMutations.get(id) == mutation) {
            scheduleFileDrain();
        }
    }

    void persistentTextChanged() {
        requestDriverIntervalReconcile();
    }

    private void scheduleFileDrain() {
        if (!fileDrainQueued.compareAndSet(false, true)) {
            return;
        }
        fileDrainSubmissions.incrementAndGet();
        try {
            fileExecutor.execute(this::drainFileMutations);
        } catch (RejectedExecutionException rejected) {
            drainFileMutations();
        }
    }

    private void drainFileMutations() {
        try {
            while (true) {
                List<FileMutation> batch = new ArrayList<>(pendingFileMutations.values());
                if (batch.isEmpty()) {
                    return;
                }
                for (FileMutation mutation : batch) {
                    if (pendingFileMutations.get(mutation.id()) != mutation) {
                        continue;
                    }
                    applyFileMutation(mutation);
                    pendingFileMutations.remove(mutation.id(), mutation);
                }
            }
        } finally {
            fileDrainQueued.set(false);
            if (!pendingFileMutations.isEmpty()) {
                scheduleFileDrain();
            }
        }
    }

    private void applyFileMutation(FileMutation mutation) {
        try {
            if (mutation.kind() == FileMutationKind.DELETE) {
                store.delete(mutation.id());
            } else {
                store.write(mutation.id(), mutation.doc());
            }
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "Failed to %s hologram %s.",
                mutation.kind() == FileMutationKind.DELETE ? "delete" : "save", mutation.id());
        }
    }

    int pendingFileMutationCount() {
        return pendingFileMutations.size();
    }

    long fileDrainSubmissionCount() {
        return fileDrainSubmissions.get();
    }

    private void shutdownFileExecutor() {
        fileExecutor.shutdown();
        try {
            if (fileExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                return;
            }
            fileExecutor.shutdownNow();
            Gloss.warn("Timed out waiting for hologram file writes; interrupted the IO worker with %d "
                + "coalesced mutation(s) still pending.", pendingFileMutations.size());
            if (!fileExecutor.awaitTermination(1L, TimeUnit.SECONDS)) {
                Gloss.log(Level.SEVERE,
                    "Gloss hologram IO worker did not stop after interruption; it is a daemon and may outlive cleanup.");
            }
        } catch (InterruptedException interrupted) {
            fileExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void flushFileMutations() {
        if (pendingFileMutations.isEmpty() && !fileDrainQueued.get()) {
            return;
        }
        scheduleFileDrain();
        CountDownLatch drained = new CountDownLatch(1);
        try {
            fileExecutor.execute(drained::countDown);
            if (!drained.await(5L, TimeUnit.SECONDS)) {
                Gloss.warn("Timed out waiting for pending hologram file writes before reload.");
            }
        } catch (RejectedExecutionException rejected) {
            drainFileMutations();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void retirePersistentHolograms(List<PersistentHologram> retiring, String operation) {
        Throwable firstFailure = null;
        int failures = 0;
        for (PersistentHologram hologram : retiring) {
            try {
                hologram.despawnAll();
            } catch (Throwable failure) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            Gloss.logExceptionStack(false, firstFailure,
                "Failed to retire %d of %d persistent holograms during %s.", failures, retiring.size(), operation);
        }
    }

    private void retireTemporaryHolograms(List<TemporaryHologramDisplay> retiring, String operation) {
        Throwable firstFailure = null;
        int failures = 0;
        for (TemporaryHologramDisplay temporary : retiring) {
            try {
                temporary.destroy();
            } catch (Throwable failure) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            Gloss.logExceptionStack(false, firstFailure,
                "Failed to retire %d of %d temporary holograms during %s.", failures, retiring.size(), operation);
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
        driverRunning = true;
        driverIntervalTicks = plugin.cfg().holograms().updateIntervalTicks();
        driverTaskId = plugin.scheduler().sr(() -> driveHolograms(false), driverIntervalTicks);
        reconcileFastDriver();
        temporaryTaskId = plugin.scheduler().sr(this::driveTemporaries, plugin.cfg().holograms().temporaryUpdateIntervalTicks());
        viewerReconcileTaskId = plugin.scheduler().sr(this::reconcileViewers, 1);
    }

    private void stopTasks() {
        driverRunning = false;
        driverReconcileQueued.set(false);
        if (driverTaskId != NO_TASK) {
            plugin.scheduler().csr(driverTaskId);
            driverTaskId = NO_TASK;
        }
        if (fastDriverTaskId != NO_TASK) {
            plugin.scheduler().csr(fastDriverTaskId);
            fastDriverTaskId = NO_TASK;
        }
        driverIntervalTicks = NO_TASK;
        if (temporaryTaskId != NO_TASK) {
            plugin.scheduler().csr(temporaryTaskId);
            temporaryTaskId = NO_TASK;
        }
        if (viewerReconcileTaskId != NO_TASK) {
            plugin.scheduler().csr(viewerReconcileTaskId);
            viewerReconcileTaskId = NO_TASK;
        }
        if (startupPurgeTaskId != NO_TASK) {
            plugin.scheduler().csr(startupPurgeTaskId);
            startupPurgeTaskId = NO_TASK;
        }
        startupChunkPurges.clear();
    }

    private void driveHolograms(boolean fast) {
        if (!plugin.cfg().holograms().enabled()) {
            if (!fast) {
                for (PersistentHologram hologram : holograms.values()) {
                    hologram.despawnAll();
                }
                sweepLeases();
            }
            return;
        }

        HologramTick tick = new HologramTick(viewerIndex);
        boolean partitioned = usesFastPartition();
        for (PersistentHologram hologram : holograms.values()) {
            if (partitioned && hologram.requiresFastRefresh() != fast) {
                continue;
            }
            schedulePersistentTick(hologram, tick);
        }

        if (!fast) {
            sweepLeases();
        }
    }

    private void schedulePersistentTick(PersistentHologram hologram, HologramTick tick) {
        if (!driverRunning) {
            return;
        }
        if (!persistentTicks.add(hologram)) {
            return;
        }
        PersistentHologram.TickAnchor tickAnchor = hologram.tickAnchor();
        if (tickAnchor == null || tickAnchor.location().getWorld() == null) {
            persistentTicks.remove(hologram);
            hologram.despawnAll();
            return;
        }
        if (!viewerIndex.anyNearby(tickAnchor.location(), viewRange())) {
            persistentTicks.remove(hologram);
            hologram.despawnAll();
            return;
        }
        Runnable update = () -> {
            try {
                if (driverRunning && plugin.cfg().holograms().enabled() && isActive(hologram)
                    && hologram.isCurrent(tickAnchor)) {
                    hologram.update(tick, tickAnchor);
                }
            } finally {
                persistentTicks.remove(hologram);
            }
        };
        if (!plugin.scheduler().runAt(tickAnchor.location(), update)) {
            persistentTicks.remove(hologram);
        }
    }

    private boolean usesFastPartition() {
        return plugin.cfg().text().functions()
            && driverIntervalTicks > ANIMATION_REFRESH_INTERVAL_TICKS;
    }

    private void requestDriverIntervalReconcile() {
        if (!driverRunning || !plugin.isEnabled() || !driverReconcileQueued.compareAndSet(false, true)) {
            return;
        }
        if (!SchedulerUtils.runGlobal(plugin, () -> {
            driverReconcileQueued.set(false);
            reconcileDriverInterval();
        })) {
            driverReconcileQueued.set(false);
        }
    }

    private void reconcileDriverInterval() {
        if (!driverRunning) {
            return;
        }
        int intervalTicks = plugin.cfg().holograms().updateIntervalTicks();
        if (driverTaskId != NO_TASK && driverIntervalTicks == intervalTicks) {
            reconcileFastDriver();
            return;
        }
        if (driverTaskId != NO_TASK) {
            plugin.scheduler().csr(driverTaskId);
        }
        driverTaskId = plugin.scheduler().sr(() -> driveHolograms(false), intervalTicks);
        driverIntervalTicks = intervalTicks;
        reconcileFastDriver();
    }

    private void reconcileFastDriver() {
        boolean required = usesFastPartition() && holograms.values().stream()
            .anyMatch(PersistentHologram::requiresFastRefresh);
        if (required && fastDriverTaskId == NO_TASK) {
            fastDriverTaskId = plugin.scheduler().sr(() -> driveHolograms(true),
                ANIMATION_REFRESH_INTERVAL_TICKS);
            return;
        }
        if (!required && fastDriverTaskId != NO_TASK) {
            plugin.scheduler().csr(fastDriverTaskId);
            fastDriverTaskId = NO_TASK;
        }
    }

    static int refreshIntervalTicks(List<String> lines, int configuredIntervalTicks) {
        for (String line : lines) {
            if (TextPipeline.requiresFastRefresh(line)) {
                return Math.min(configuredIntervalTicks, ANIMATION_REFRESH_INTERVAL_TICKS);
            }
        }
        return configuredIntervalTicks;
    }

    private void driveTemporaries() {
        boolean enabled = plugin.cfg().holograms().enabled();
        HologramTick tick = new HologramTick(viewerIndex);
        for (TemporaryHologramDisplay temporary : temporaries) {
            temporary.scheduleDrive(tick, enabled);
        }
    }

    public boolean forEachNearbyViewer(Location anchor, double rangeSquared, Consumer<Player> action) {
        World world = anchor.getWorld();
        if (world == null || rangeSquared < 0.0D || !Double.isFinite(rangeSquared)) {
            return false;
        }
        for (HologramTick.Viewer viewer : viewerIndex.nearby(anchor, Math.sqrt(rangeSquared))) {
            action.accept(viewer.player());
        }
        return true;
    }

    private void captureOnlineViewers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            captureViewer(player);
        }
    }

    private void captureViewer(Player player) {
        captureViewer(player, 0L);
    }

    private void captureViewer(Player player, long delayTicks) {
        Runnable capture = () -> {
            if (player.isOnline()) {
                updateViewerLocation(player, player.getLocation());
            }
        };
        FoliaScheduler.runEntity(plugin, player, capture, delayTicks,
            () -> viewerIndex.remove(player.getUniqueId()));
    }

    private void updateViewerLocation(Player player, Location location) {
        if (viewerIndex.update(player, location)) {
            refreshPersonalizedTracking(player);
        }
    }

    private void reconcileViewers() {
        viewerIndex.reconcileBatch(VIEWER_RECONCILE_BATCH, this::captureViewer);
    }

    private void sweepLeases() {
        long now = System.nanoTime();
        if (leased.isEmpty() || now < nextLeaseSweepNanos) {
            return;
        }
        nextLeaseSweepNanos = now + LEASE_SWEEP_INTERVAL_NANOS;
        for (Map.Entry<UUID, TextDisplay> entry : leased.entrySet()) {
            UUID displayId = entry.getKey();
            TextDisplay display = entry.getValue();
            Runnable inspect = () -> {
                if (!display.isValid()) {
                    leased.remove(displayId, display);
                }
            };
            if (!plugin.scheduler().runEntity(display, inspect)) {
                leased.remove(displayId, display);
            }
        }
    }

    void prunePlayer(UUID playerId) {
        ViewerWorkQueue queue = viewerWorkQueues.remove(playerId);
        if (queue != null) {
            queue.tasks.clear();
        }
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

        if (registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applyDelta(delta))) {
            return;
        }

        Gloss.warnThrottled("hologram-hotload-scheduling",
            "Hologram hot reload could not reach the server thread; the change will be retried.");
    }

    /**
     * Spawns and despawns display entities, so it never runs on the watchdog IO thread. The poll
     * that produced the delta is pure file work; only this half needs the server context.
     */
    private void applyDelta(DocumentDelta delta) {
        for (String id : delta.loaded()) {
            GlossDocument<HologramDoc> document = registry.get(delta, id);
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
        requestDriverIntervalReconcile();
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

    void sweepLoadedChunks() {
        startupChunkPurges.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                startupChunkPurges.add(new ChunkPurge(world, chunk.getX(), chunk.getZ()));
            }
        }
        if (!startupChunkPurges.isEmpty() && startupPurgeTaskId == NO_TASK) {
            startupPurgeTaskId = plugin.scheduler().sr(this::drainStartupChunkPurges, 1);
        }
    }

    void drainStartupChunkPurges() {
        for (int scheduled = 0; scheduled < STARTUP_PURGE_BATCH; scheduled++) {
            ChunkPurge purge = startupChunkPurges.poll();
            if (purge == null) {
                stopStartupChunkPurge();
                return;
            }
            scheduleChunkPurge(purge.world(), purge.chunkX(), purge.chunkZ());
        }
        if (startupChunkPurges.isEmpty()) {
            stopStartupChunkPurge();
        }
    }

    private void stopStartupChunkPurge() {
        if (startupPurgeTaskId != NO_TASK) {
            plugin.scheduler().csr(startupPurgeTaskId);
            startupPurgeTaskId = NO_TASK;
        }
    }

    int startupChunkPurgeCount() {
        return startupChunkPurges.size();
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

    private static Runnable once(Runnable action) {
        AtomicBoolean executed = new AtomicBoolean();
        return () -> {
            if (executed.compareAndSet(false, true)) {
                action.run();
            }
        };
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

    static final class ViewerWorkQueue {
        private final Map<String, Runnable> tasks = new ConcurrentHashMap<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();

        void put(String key, Runnable work) {
            tasks.put(key, work);
        }

        int pendingCount() {
            return tasks.size();
        }

        Runnable remove(String key) {
            return tasks.remove(key);
        }
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
            UUID playerId = event.getPlayer().getUniqueId();
            viewerIndex.remove(playerId);
            prunePlayer(playerId);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerJoin(PlayerJoinEvent event) {
            viewerIndex.update(event.getPlayer(), event.getPlayer().getLocation());
            reconcileViewerLifecycle(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlayerMove(PlayerMoveEvent event) {
            if (!(event instanceof PlayerTeleportEvent) && event.getTo() != null) {
                updateViewerLocation(event.getPlayer(), event.getTo());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlayerTeleport(PlayerTeleportEvent event) {
            if (event.getTo() != null) {
                viewerIndex.update(event.getPlayer(), event.getTo());
                reconcileViewerLifecycle(event.getPlayer());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
            viewerIndex.update(event.getPlayer(), event.getPlayer().getLocation());
            reconcileViewerLifecycle(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerRespawn(PlayerRespawnEvent event) {
            viewerIndex.update(event.getPlayer(), event.getRespawnLocation());
            reconcileViewerLifecycle(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onVehicleMove(VehicleMoveEvent event) {
            reconcilePassengers(event.getVehicle().getPassengers());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onVehicleEnter(VehicleEnterEvent event) {
            if (event.getEntered() instanceof Player player) {
                captureViewer(player, 1L);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onVehicleExit(VehicleExitEvent event) {
            if (event.getExited() instanceof Player player) {
                captureViewer(player, 1L);
            }
        }
    }

    private void reconcilePassengers(List<Entity> passengers) {
        for (Entity passenger : passengers) {
            if (passenger instanceof Player player) {
                captureViewer(player, 1L);
            }
            if (!passenger.getPassengers().isEmpty()) {
                reconcilePassengers(passenger.getPassengers());
            }
        }
    }

    private void reconcileViewerLifecycle(Player player) {
        for (TemporaryHologramDisplay temporary : temporaries) {
            temporary.reconcileVisibilityFor(player);
        }
        invalidatePersonalizedTracking(player, true);
    }

    private void invalidatePersonalizedTracking(Player player, boolean clearClientText) {
        for (PersistentHologram hologram : holograms.values()) {
            hologram.invalidateTrackingFor(player, clearClientText);
        }
    }

    private void refreshPersonalizedTracking(Player player) {
        for (PersistentHologram hologram : holograms.values()) {
            hologram.refreshTrackingFor(player);
        }
    }
}
