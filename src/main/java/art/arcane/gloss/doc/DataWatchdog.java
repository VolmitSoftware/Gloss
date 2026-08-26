package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Hot-reload driver. Every registered pass runs on one dedicated IO thread, never on the server
 * thread: a poll is a directory walk plus a read and a parse, and doing that on the main thread
 * stalls the server in proportion to the number of documents on disk.
 *
 * <p>A registered poll body may therefore touch nothing but files and its own state. Passes whose
 * apply phase talks to Bukkit hop it themselves — {@code HologramService}, {@code MenuCatalog},
 * {@code ImageAssets}, {@code PreviewDocumentRegistry} and the {@code config} entry all schedule their apply onto the
 * global (or owning region) context and keep only the stat/read/parse off-thread. The scheduler may
 * request work at the configured interval. Requests arriving during a scan or its queued apply
 * phase collapse into one trailing pass that reads the latest state.
 */
public final class DataWatchdog {
    private static final int NO_TASK = -1;
    private static final long SHUTDOWN_WAIT_MS = 2000L;
    private static final long AUTOMATIC_BATCH_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3L);

    private final Gloss plugin;
    private final List<Entry> entries;
    private final HotloadBatchGate batchGate;
    private final HotloadBatch hotloadBatch;
    private final HotloadFeedback hotloadFeedback;
    private final AtomicLong lifecycleGeneration;
    private volatile ExecutorService io;
    private int taskId;

    private record Entry(String name, Runnable poll) {
    }

    public DataWatchdog(Gloss plugin) {
        this(plugin, System::nanoTime);
    }

    DataWatchdog(Gloss plugin, LongSupplier clock) {
        this.plugin = plugin;
        this.entries = new CopyOnWriteArrayList<>();
        this.batchGate = new HotloadBatchGate(AUTOMATIC_BATCH_COOLDOWN_NANOS, clock);
        this.hotloadBatch = new HotloadBatch();
        this.hotloadFeedback = new HotloadFeedback(plugin);
        this.lifecycleGeneration = new AtomicLong();
        this.taskId = NO_TASK;
    }

    public void register(String name, Runnable poll) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(poll, "poll");
        unregister(name);
        entries.add(new Entry(name, poll));
    }

    public void unregister(String name) {
        entries.removeIf(entry -> entry.name().equals(name));
    }

    public synchronized void start(int intervalTicks) {
        if (taskId != NO_TASK) {
            return;
        }
        batchGate.cancel();
        hotloadBatch.clear();
        lifecycleGeneration.incrementAndGet();
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Gloss-Watchdog-IO");
            thread.setDaemon(true);
            return thread;
        });
        taskId = plugin.scheduler().ar(this::pump, intervalTicks);
    }

    public synchronized void stop() {
        if (taskId == NO_TASK) {
            return;
        }
        plugin.scheduler().car(taskId);
        taskId = NO_TASK;
        lifecycleGeneration.incrementAndGet();
        batchGate.cancel();
        hotloadBatch.clear();
        ExecutorService current = io;
        io = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void restart(int intervalTicks) {
        if (taskId == NO_TASK) {
            start(intervalTicks);
            return;
        }
        plugin.scheduler().car(taskId);
        taskId = plugin.scheduler().ar(this::pump, intervalTicks);
    }

    public void deferAutomaticPass() {
        batchGate.deferFromNow();
    }

    public void recordHotload(String kind, int changes) {
        hotloadBatch.record(kind, changes);
    }

    private void pump() {
        batchGate.request();
        dispatchQueuedPass();
    }

    private void dispatchQueuedPass() {
        ExecutorService current = io;
        if (current == null || !batchGate.tryStart()) {
            return;
        }
        long generation = lifecycleGeneration.get();
        try {
            current.execute(() -> runPass(generation));
        } catch (RejectedExecutionException rejected) {
            if (generation == lifecycleGeneration.get()) {
                batchGate.retry();
            }
        }
    }

    private void runPass(long generation) {
        try (HotloadReconciliationBudget budget = HotloadReconciliationBudget.open()) {
            tick(generation);
        } finally {
            completeAfterApplyBatch(generation);
        }
    }

    private void completeAfterApplyBatch(long generation) {
        if (generation != lifecycleGeneration.get()) {
            return;
        }
        if (plugin != null && SchedulerUtils.runGlobal(plugin, () -> completePass(generation, true))) {
            return;
        }
        completePass(generation, false);
    }

    @SuppressWarnings("removal")
    private void completePass(long generation, boolean deliverFeedback) {
        if (generation != lifecycleGeneration.get()) {
            return;
        }
        HotloadBatch.Snapshot hotloads = hotloadBatch.drain();
        try {
            if (deliverFeedback) {
                hotloadFeedback.deliver(hotloads);
            }
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            Gloss.logExceptionStack(false, failure, "Hot reload feedback delivery failed.");
        } finally {
            batchGate.complete();
            dispatchQueuedPass();
        }
    }

    @SuppressWarnings("removal")
    void tick() {
        tick(lifecycleGeneration.get());
    }

    @SuppressWarnings("removal")
    private void tick(long generation) {
        for (Entry entry : entries) {
            if (generation != lifecycleGeneration.get()) {
                return;
            }
            try {
                entry.poll().run();
            } catch (ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                Gloss.logExceptionStack(false, failure, "%s: hot reload pass failed.", entry.name());
            }
        }
    }
}
