package art.arcane.gloss.motion;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.hologram.AnimatorBudgetWindow;
import art.arcane.gloss.hologram.AnimatorLoopPolicy;
import art.arcane.gloss.hologram.RotatingOrder;
import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class TransformStreamer {
    public static final String THREAD_NAME = "Gloss Motion";
    static final long IDLE_EXIT_MILLIS = 2000L;
    private static final long IDLE_POLL_MILLIS = 50L;
    private static final long PACKET_BUDGET_WINDOW_MILLIS = 1000L;
    private static final long REPORT_INTERVAL_MILLIS = 10_000L;
    private static final long STOP_JOIN_MILLIS = 1000L;
    private static final Object[] EMPTY_KEYS = new Object[0];

    public interface Sink {
        void send(List<Player> viewers, PacketWrapper<?> packet, BooleanSupplier live);
    }

    private static final class Publication implements BooleanSupplier {
        private final TransformFrameSource source;
        private volatile boolean retired;
        private volatile long lastFrameMs = Long.MIN_VALUE;

        private Publication(TransformFrameSource source) {
            this.source = source;
        }

        @Override
        public boolean getAsBoolean() {
            return !retired;
        }
    }

    private final Supplier<GlossConfig> config;
    private final Sink sink;
    private final LongSupplier clock;
    private final long idleExitMillis;
    private final Map<Object, Publication> sources = new ConcurrentHashMap<>();
    private final AtomicLong membershipGeneration = new AtomicLong();
    private final RotatingOrder<Object> order = new RotatingOrder<>(EMPTY_KEYS);
    private final AnimatorBudgetWindow budgetWindow = new AnimatorBudgetWindow(PACKET_BUDGET_WINDOW_MILLIS);
    private final Object workerLock = new Object();
    private Thread worker;
    private volatile boolean stopped = true;
    private volatile long settledIntervalMillis;

    public TransformStreamer(Gloss plugin) {
        this(plugin::cfg, TransformStreamer::broadcast, System::currentTimeMillis, IDLE_EXIT_MILLIS);
    }

    TransformStreamer(Supplier<GlossConfig> config, Sink sink, LongSupplier clock, long idleExitMillis) {
        this.config = config;
        this.sink = sink;
        this.clock = clock;
        this.idleExitMillis = idleExitMillis;
    }

    public void start() {
        stopped = false;
        if (!sources.isEmpty()) {
            ensureWorker();
        }
    }

    public void stop() {
        stopped = true;
        for (Publication publication : sources.values()) {
            publication.retired = true;
        }
        if (!sources.isEmpty()) {
            sources.clear();
            membershipGeneration.incrementAndGet();
        }
        Thread active;
        synchronized (workerLock) {
            active = worker;
        }
        if (active == null) {
            return;
        }
        active.interrupt();
        try {
            active.join(STOP_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public void publish(Object key, TransformFrameSource source) {
        Publication previous = sources.put(key, new Publication(source));
        if (previous == null) {
            membershipGeneration.incrementAndGet();
        } else {
            previous.retired = true;
        }
        ensureWorker();
    }

    public void remove(Object key) {
        Publication removed = sources.remove(key);
        if (removed != null) {
            removed.retired = true;
            membershipGeneration.incrementAndGet();
        }
    }

    public int sourceCount() {
        return sources.size();
    }

    public long settledIntervalMillis() {
        return settledIntervalMillis;
    }

    boolean workerRunning() {
        synchronized (workerLock) {
            return worker != null;
        }
    }

    int pass(long nowMs) {
        GlossConfig.Rigs rigs = config.get().modules().rigs();
        int budget = Math.max(1, rigs.transformPacketBudget());
        int maxFps = Math.max(1, rigs.maxMotionFps());
        long windowMs = budgetWindow.advance(nowMs);
        budgetWindow.discardExpired(windowMs);
        long inWindow = budgetWindow.recipientsInWindow();
        long remaining = Math.max(0L, (long) budget - inWindow);
        if (remaining == 0L) {
            return 0;
        }

        Object[] keys = order.order(sources, membershipGeneration);
        int start = order.start(keys);
        int visited = 0;
        int sends = 0;
        int reserved = 0;
        try {
            while (visited < keys.length && remaining > 0L) {
                Object key = keys[(start + visited) % keys.length];
                visited++;
                Publication publication = sources.get(key);
                if (publication == null || publication.retired) {
                    continue;
                }
                TransformFrameSource source = publication.source;
                if (!source.live()) {
                    continue;
                }
                long intervalMs = 1000L / Math.max(1, Math.min(maxFps, source.fps()));
                if (publication.lastFrameMs != Long.MIN_VALUE && publication.lastFrameMs + intervalMs > nowMs) {
                    continue;
                }
                List<Player> viewers = source.viewers();
                if (viewers.isEmpty()) {
                    publication.lastFrameMs = nowMs;
                    continue;
                }
                if (viewers.size() > remaining && inWindow > 0L) {
                    continue;
                }
                publication.lastFrameMs = nowMs;
                // A composed frame goes out whole. Stopping part way leaves some parts of the rig
                // on the new frame and some on the old, and the sender has already recorded them
                // as sent, so the stale ones never catch up. The overshoot is one frame and the
                // window swallows it on the next pass.
                List<PacketWrapper<?>> packets = source.compose(nowMs);
                for (PacketWrapper<?> packet : packets) {
                    if (publication.retired) {
                        break;
                    }
                    sink.send(viewers, packet, publication);
                    sends++;
                    reserved += viewers.size();
                    remaining -= viewers.size();
                    inWindow += viewers.size();
                }
            }
        } finally {
            order.advance(start, visited, keys.length);
            budgetWindow.record(windowMs, reserved);
        }
        return sends;
    }

    private void ensureWorker() {
        synchronized (workerLock) {
            if (stopped || worker != null) {
                return;
            }
            Thread thread = new Thread(this::runLoop, THREAD_NAME);
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }
    }

    private void runLoop() {
        long floor = AnimatorLoopPolicy.floorMillis(config.get().modules().rigs().maxMotionFps());
        long interval = floor;
        long idleSinceMs = -1L;
        long reportAtMs = clock.getAsLong() + REPORT_INTERVAL_MILLIS;
        long reportSends = 0L;
        long reportPasses = 0L;
        settledIntervalMillis = interval;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(sources.isEmpty() ? IDLE_POLL_MILLIS : interval);
            } catch (InterruptedException interrupted) {
                break;
            }
            if (stopped) {
                break;
            }
            if (sources.isEmpty()) {
                long nowMs = clock.getAsLong();
                if (idleSinceMs < 0L) {
                    idleSinceMs = nowMs;
                }
                if (nowMs - idleSinceMs >= idleExitMillis) {
                    break;
                }
                continue;
            }

            idleSinceMs = -1L;
            floor = AnimatorLoopPolicy.floorMillis(config.get().modules().rigs().maxMotionFps());
            long startNanos = System.nanoTime();
            long nowMs = clock.getAsLong();
            try {
                reportSends += pass(nowMs);
            } catch (Throwable failure) {
                Gloss.logExceptionStackThrottled(false, "transform-streamer-pass", failure,
                    "Transform streamer pass failed; continuing.");
            }
            reportPasses++;
            double passMillis = (System.nanoTime() - startNanos) / 1.0E6D;
            interval = AnimatorLoopPolicy.nextIntervalMillis(interval, passMillis, floor);
            settledIntervalMillis = interval;
            if (config.get().debug().animator() && nowMs >= reportAtMs) {
                Gloss.verbose("Motion interval=%dms sources=%d sends=%d passes=%d (10s window).",
                    interval, sources.size(), reportSends, reportPasses);
                reportAtMs = nowMs + REPORT_INTERVAL_MILLIS;
                reportSends = 0L;
                reportPasses = 0L;
            }
        }

        synchronized (workerLock) {
            if (worker == Thread.currentThread()) {
                worker = null;
            }
        }
        if (!stopped && !sources.isEmpty()) {
            ensureWorker();
        }
    }

    private static void broadcast(List<Player> viewers, PacketWrapper<?> packet, BooleanSupplier live) {
        PacketUtils.broadcast(viewers, packet, live);
    }
}
