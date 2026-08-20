package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Hot-reload driver. Every registered pass runs on one dedicated IO thread, never on the server
 * thread: a poll is a directory walk plus a read and a parse, and eleven of them fired four times a
 * second is a main-thread stall that scales with the number of documents on disk.
 *
 * <p>A registered poll body may therefore touch nothing but files and its own state. Passes whose
 * apply phase talks to Bukkit hop it themselves — {@code HologramService}, {@code MenuCatalog},
 * {@code ImageAssets}, {@code PreviewDocumentRegistry} and the {@code config} entry all schedule their apply onto the
 * global (or owning region) context and keep only the stat/read/parse off-thread. Cadence is
 * unchanged: the scheduler still ticks at the configured interval, and a pass that outruns the
 * interval simply skips the tick it would have overlapped rather than queueing a backlog.
 */
public final class DataWatchdog {
    private static final int NO_TASK = -1;
    private static final long SHUTDOWN_WAIT_MS = 2000L;

    private final Gloss plugin;
    private final List<Entry> entries;
    private final AtomicBoolean passInFlight;
    private volatile ExecutorService io;
    private int taskId;

    private record Entry(String name, Runnable poll) {
    }

    public DataWatchdog(Gloss plugin) {
        this.plugin = plugin;
        this.entries = new CopyOnWriteArrayList<>();
        this.passInFlight = new AtomicBoolean();
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

    public void start(int intervalTicks) {
        if (taskId != NO_TASK) {
            return;
        }
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Gloss-Watchdog-IO");
            thread.setDaemon(true);
            return thread;
        });
        taskId = plugin.scheduler().ar(this::pump, intervalTicks);
    }

    public void stop() {
        if (taskId == NO_TASK) {
            return;
        }
        plugin.scheduler().car(taskId);
        taskId = NO_TASK;
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
        // A pass discarded by shutdownNow never reaches its finally, and a stuck flag would leave
        // the watchdog permanently silent after the next start().
        passInFlight.set(false);
    }

    public void restart(int intervalTicks) {
        stop();
        start(intervalTicks);
    }

    private void pump() {
        ExecutorService current = io;
        if (current == null) {
            return;
        }
        if (!passInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            current.execute(this::runPass);
        } catch (RejectedExecutionException rejected) {
            passInFlight.set(false);
        }
    }

    private void runPass() {
        try {
            tick();
        } finally {
            passInFlight.set(false);
        }
    }

    @SuppressWarnings("removal")
    void tick() {
        for (Entry entry : entries) {
            try {
                entry.poll().run();
            } catch (ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                String message = failure.getMessage();
                Gloss.log(Level.WARNING, "%s: hot reload pass failed: %s", entry.name(),
                    message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message);
            }
        }
    }
}
