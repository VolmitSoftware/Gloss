package art.arcane.gloss.sky;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.service.PaperBridges;
import art.arcane.gloss.state.PlayerSections;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SkyService implements GlossService, Listener {
    public static final String NAME = "sky";
    public static final String SECTION = "sky";
    private static final Map<String, Object> RECOVERY = Map.of("restore", true);

    private final Gloss plugin;
    private final PlayerSections sections;
    private final BorderApplier borders;
    private final Transport transport;
    private final Executor io;
    private final SkyOwnershipStack stack = new SkyOwnershipStack();
    private final ConcurrentMap<UUID, Fade> fades = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Lane> retrying = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Lane> lanes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Player> viewers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Player> restoring = new ConcurrentHashMap<>();
    private final Set<UUID> queuedFades = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong lifecycle = new AtomicLong();
    private int fadeTaskId = -1;

    public SkyService(Gloss plugin, PlayerSections sections) {
        this(new Dependencies(plugin, sections, PaperBridges.load("org.bukkit.entity.Player",
            "art.arcane.gloss.paper.PaperWorldBorderBridge", BorderApplier.class).orElseGet(PacketWorldBorder::new),
            (viewer, action, retired) -> FoliaScheduler.runEntity(plugin, viewer, action, 0, retired), worker(plugin)));
    }

    SkyService(Dependencies dependencies) {
        this.plugin = Objects.requireNonNull(dependencies.plugin());
        this.sections = Objects.requireNonNull(dependencies.sections());
        this.borders = Objects.requireNonNull(dependencies.borders());
        this.transport = Objects.requireNonNull(dependencies.transport());
        this.io = Objects.requireNonNull(dependencies.io());
    }

    private static Executor worker(Gloss plugin) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(plugin.cfg().modules().sky().maxPendingOperations()), task -> {
                Thread thread = new Thread(task, "Gloss sky persistence");
                thread.setDaemon(true);
                return thread;
            });
        worker.allowCoreThreadTimeOut(true);
        return worker;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (fadeTaskId == -1) {
            int interval = plugin.cfg().modules().sky().fadeIntervalTicks();
            fadeTaskId = plugin.scheduler().sr(() -> tickFades(interval), interval);
        }
    }

    @Override
    public void disable() {
        lifecycle.incrementAndGet();
        HandlerList.unregisterAll(this);
        if (fadeTaskId != -1) {
            plugin.scheduler().csr(fadeTaskId);
            fadeTaskId = -1;
        }
        for (Player viewer : viewers.values()) {
            submit(viewer, Kind.RESET, null, null);
        }
        for (Lane lane : lanes.values()) {
            submit(lane.viewer, Kind.RESET, null, null);
        }
        fades.clear();
        queuedFades.clear();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().sky().equals(next.modules().sky());
    }

    public boolean enabled() {
        return plugin.cfg().modules().sky().enabled();
    }

    public void apply(Player viewer, SkyOverride override) {
        submit(Objects.requireNonNull(viewer), Kind.APPLY, Objects.requireNonNull(override), null);
    }

    public void release(Player viewer, String purpose) {
        submit(Objects.requireNonNull(viewer), Kind.RELEASE, null, Objects.requireNonNull(purpose));
    }

    public Optional<SkyOverride> top(UUID viewerId) {
        return stack.top(viewerId);
    }

    public List<String> purposes(UUID viewerId) {
        return stack.purposes(viewerId);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreJournalled(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        submit(event.getPlayer(), Kind.QUIT, null, null);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        submit(event.getEntity(), Kind.RESET, null, null);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        submit(event.getPlayer(), Kind.RESET, null, null);
    }

    public void restoreJournalled(Player viewer) {
        submit(Objects.requireNonNull(viewer), Kind.RECOVER, null, null);
    }

    void tickFades(int ticks) {
        for (Map.Entry<UUID, Lane> retry : retrying.entrySet()) {
            if (retrying.remove(retry.getKey(), retry.getValue())) {
                prepare(retry.getKey(), retry.getValue());
            }
        }
        for (Player viewer : restoring.values()) {
            submit(viewer, Kind.RESET, null, null);
        }
        for (Map.Entry<UUID, Fade> entry : fades.entrySet()) {
            UUID id = entry.getKey();
            Player viewer = viewers.get(id);
            if (viewer == null || !queuedFades.add(id)) {
                continue;
            }
            Runnable retired = () -> queuedFades.remove(id);
            boolean accepted;
            try {
            accepted = transport.execute(viewer, () -> {
                try {
                    Fade previous = entry.getValue();
                    if (!viewer.isOnline() || fades.get(id) != previous) {
                        return;
                    }
                    Fade advanced = previous.advanced(ticks);
                    viewer.setPlayerTime(SkyFade.timeAt(advanced.from(), advanced.to(), advanced.fadeTicks(),
                        advanced.elapsedTicks()), false);
                    if (advanced.done()) {
                        fades.remove(id, previous);
                    } else {
                        fades.replace(id, previous, advanced);
                    }
                } catch (RuntimeException failure) {
                    report(id, "advancing a fade", failure);
                    restoring.put(id, viewer);
                } finally {
                    queuedFades.remove(id);
                }
            }, retired);
            } catch (RuntimeException failure) {
                retired.run();
                report(id, "scheduling a fade", failure);
                continue;
            }
            if (!accepted) {
                retired.run();
            }
        }
    }

    private void submit(Player viewer, Kind kind, SkyOverride override, String purpose) {
        UUID id = viewer.getUniqueId();
        while (true) {
            Lane lane = lanes.computeIfAbsent(id, ignored -> new Lane(viewer));
            boolean start;
            synchronized (lane) {
                if (lanes.get(id) != lane) {
                    continue;
                }
                lane.viewer = viewer;
                if (kind.cleanup()) {
                    for (Operation queued : lane.operations) {
                        if (queued.kind() == kind) {
                            return;
                        }
                    }
                    lane.generation++;
                    fades.remove(id);
                } else if (lane.operations.size() >= plugin.cfg().modules().sky().maxPendingPerViewer()
                    || !reserve()) {
                    report(id, "queuing an override", new IllegalStateException("Sky operation capacity exhausted"));
                    if (lane.operations.isEmpty()) {
                        lanes.remove(id, lane);
                    }
                    return;
                }
                lane.operations.addLast(new Operation(viewer, kind, override, purpose, lifecycle.get(), lane.generation, new AtomicBoolean()));
                start = lane.operations.size() == 1;
            }
            if (start || kind.cleanup() && retrying.remove(id, lane)) {
                prepare(id, lane);
            }
            return;
        }
    }

    private boolean reserve() {
        int limit = plugin.cfg().modules().sky().maxPendingOperations();
        while (true) {
            int used = pending.get();
            if (used >= limit) {
                return false;
            }
            if (pending.compareAndSet(used, used + 1)) {
                return true;
            }
        }
    }

    private void prepare(UUID id, Lane lane) {
        try {
            io.execute(() -> prepareOperation(id, lane));
        } catch (RejectedExecutionException failure) {
            report(id, "scheduling recovery persistence", failure);
            retrying.put(id, lane);
        }
    }

    private void prepareOperation(UUID id, Lane lane) {
        Operation operation = head(lane);
        if (operation == null) {
            return;
        }
        try {
            if (operation.kind() == Kind.APPLY) {
                if (operation.generation() != lifecycle.get() || operation.viewerGeneration() != lane.generation) {
                    finish(id, lane, operation);
                    return;
                }
                sections.writeChecked(id, SECTION, RECOVERY);
            } else if (operation.kind() == Kind.RECOVER && sections.read(id, SECTION).isEmpty()) {
                finish(id, lane, operation);
                return;
            }
            Runnable retired = () -> {
                if (operation.kind() == Kind.QUIT) {
                    forget(id);
                } else if (operation.kind().cleanup()) {
                    restoring.put(id, operation.viewer());
                }
                finish(id, lane, operation);
            };
            if (!transport.execute(operation.viewer(), () -> applyOwner(id, lane, operation), retired)
                && !operation.finished().get()) {
                if (operation.kind() == Kind.QUIT) {
                    forget(id);
                    finish(id, lane, operation);
                } else {
                    retrying.put(id, lane);
                }
            }
        } catch (IOException | RuntimeException failure) {
            report(id, "preparing recovery state", failure);
            finish(id, lane, operation);
        }
    }

    private void applyOwner(UUID id, Lane lane, Operation operation) {
        boolean clear = false;
        try {
            Player viewer = operation.viewer();
            if (!viewer.isOnline() || operation.kind() == Kind.APPLY
                && (operation.generation() != lifecycle.get() || operation.viewerGeneration() != lane.generation)) {
                if (operation.kind() == Kind.QUIT) {
                    forget(id);
                }
                finish(id, lane, operation);
                return;
            }
            switch (operation.kind()) {
                case APPLY -> {
                    SkyOverride previous = stack.effective(id).orElse(null);
                    stack.push(id, operation.override());
                    viewers.put(id, viewer);
                    show(viewer, previous, stack.effective(id).orElseThrow());
                }
                case RELEASE -> {
                    if (stack.purposes(id).contains(operation.purpose())) {
                        SkyOverride previous = stack.effective(id).orElse(null);
                        stack.release(id, operation.purpose());
                        Optional<SkyOverride> next = stack.effective(id);
                        if (next.isPresent()) {
                            show(viewer, previous, next.get());
                        } else {
                            restoreReal(viewer);
                            forget(id);
                            clear = true;
                        }
                    }
                }
                case RESET, RECOVER, QUIT -> {
                    restoreReal(viewer);
                    forget(id);
                    clear = true;
                }
            }
        } catch (RuntimeException failure) {
            report(id, "applying or restoring the sky", failure);
            if (operation.kind() != Kind.QUIT) {
                restoring.put(id, operation.viewer());
            }
        }
        if (clear) {
            clearJournal(id, lane, operation);
        } else {
            finish(id, lane, operation);
        }
    }

    private void clearJournal(UUID id, Lane lane, Operation operation) {
        try {
            io.execute(() -> {
                try {
                    sections.writeChecked(id, SECTION, Map.of());
                } catch (IOException | RuntimeException failure) {
                    report(id, "clearing restored recovery state", failure);
                } finally {
                    finish(id, lane, operation);
                }
            });
        } catch (RejectedExecutionException failure) {
            report(id, "scheduling restored recovery cleanup", failure);
            finish(id, lane, operation);
        }
    }

    private static Operation head(Lane lane) {
        synchronized (lane) {
            return lane.operations.peekFirst();
        }
    }

    private void finish(UUID id, Lane lane, Operation operation) {
        if (operation == null || !operation.finished().compareAndSet(false, true)) {
            return;
        }
        boolean next;
        synchronized (lane) {
            if (lane.operations.peekFirst() != operation) {
                return;
            }
            Operation completed = lane.operations.pollFirst();
            if (completed == null) {
                return;
            }
            if (!completed.kind().cleanup()) {
                pending.decrementAndGet();
            }
            next = !lane.operations.isEmpty();
            if (!next) {
                lanes.remove(id, lane);
                retrying.remove(id, lane);
            }
        }
        if (next) {
            prepare(id, lane);
        }
    }

    private void forget(UUID id) {
        stack.forget(id);
        fades.remove(id);
        queuedFades.remove(id);
        viewers.remove(id);
        restoring.remove(id);
    }

    private void show(Player viewer, SkyOverride previous, SkyOverride next) {
        Long oldTime = previous == null ? null : previous.time();
        String oldWeather = previous == null ? null : previous.weather();
        SkyOverride.Border oldBorder = previous == null ? null : previous.border();
        if (!Objects.equals(oldTime, next.time())) {
            if (next.time() == null) {
                fades.remove(viewer.getUniqueId());
                viewer.resetPlayerTime();
            } else {
                startTime(viewer, next);
            }
        }
        if (!Objects.equals(oldWeather, next.weather())) {
            if (next.weather() == null) {
                viewer.resetPlayerWeather();
            } else {
                viewer.setPlayerWeather(weather(next.weather()));
            }
        }
        if (!Objects.equals(oldBorder, next.border())) {
            if (next.border() == null) {
                borders.restore(viewer);
            } else {
                borders.apply(viewer, next.border());
            }
        }
    }

    private void startTime(Player viewer, SkyOverride override) {
        long target = override.time();
        if (override.fadeTicks() <= 0) {
            fades.remove(viewer.getUniqueId());
            viewer.setPlayerTime(target, false);
            return;
        }
        long from = viewer.getPlayerTime();
        fades.put(viewer.getUniqueId(), new Fade(from, target, override.fadeTicks(), 0));
        viewer.setPlayerTime(SkyFade.timeAt(from, target, override.fadeTicks(), 0), false);
    }

    private void restoreReal(Player viewer) {
        viewer.resetPlayerTime();
        viewer.resetPlayerWeather();
        borders.restore(viewer);
    }

    private static WeatherType weather(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "rain", "downfall", "thunder", "storm" -> WeatherType.DOWNFALL;
            default -> WeatherType.CLEAR;
        };
    }

    private static void report(UUID id, String action, Throwable failure) {
        Gloss.logExceptionStackThrottled(false, "sky:" + id + ":" + action, failure,
            "Sky for %s failed while %s; recovery state is retained until restoration succeeds.", id, action);
    }

    record Dependencies(Gloss plugin, PlayerSections sections, BorderApplier borders, Transport transport, Executor io) {
    }

    @FunctionalInterface
    interface Transport {
        boolean execute(Player viewer, Runnable action, Runnable retired);
    }

    private enum Kind {
        APPLY, RELEASE, RESET, RECOVER, QUIT;

        boolean cleanup() {
            return this == RESET || this == RECOVER || this == QUIT;
        }
    }

    private record Operation(Player viewer, Kind kind, SkyOverride override, String purpose, long generation, long viewerGeneration, AtomicBoolean finished) {
    }

    private static final class Lane {
        private volatile Player viewer;
        private volatile long generation;
        private final ArrayDeque<Operation> operations = new ArrayDeque<>();

        private Lane(Player viewer) {
            this.viewer = viewer;
        }
    }

    private record Fade(long from, long to, int fadeTicks, int elapsedTicks) {
        private Fade advanced(int ticks) {
            return new Fade(from, to, fadeTicks, Math.min(fadeTicks, elapsedTicks + ticks));
        }

        private boolean done() {
            return elapsedTicks >= fadeTicks;
        }
    }
}
