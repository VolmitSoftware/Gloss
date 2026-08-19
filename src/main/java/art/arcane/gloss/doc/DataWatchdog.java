package art.arcane.gloss.doc;

import art.arcane.gloss.Gloss;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public final class DataWatchdog {
    private static final int NO_TASK = -1;

    private final Gloss plugin;
    private final List<Entry> entries;
    private int taskId;

    private record Entry(String name, Runnable poll) {
    }

    public DataWatchdog(Gloss plugin) {
        this.plugin = plugin;
        this.entries = new CopyOnWriteArrayList<>();
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
        taskId = plugin.scheduler().sr(this::tick, intervalTicks);
    }

    public void stop() {
        if (taskId == NO_TASK) {
            return;
        }
        plugin.scheduler().csr(taskId);
        taskId = NO_TASK;
    }

    public void restart(int intervalTicks) {
        stop();
        start(intervalTicks);
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
