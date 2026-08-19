package art.arcane.gloss.text;

import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.plugin.Plugin;

final class ServerTickSampler {
    private static final long SAMPLE_TICKS = 20L;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    private static final double SMOOTHING = 0.25D;

    private final Plugin plugin;
    private volatile double tps;
    private long lastSampleNanos;
    private SchedulerUtils.TaskHandle task;

    ServerTickSampler(Plugin plugin) {
        this.plugin = plugin;
        this.tps = 20.0D;
    }

    void enable() {
        disable();
        lastSampleNanos = 0L;
        tps = 20.0D;
        task = SchedulerUtils.scheduleSyncTask(plugin, SAMPLE_TICKS, this::sample, false);
    }

    void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    double tps() {
        return tps;
    }

    private void sample() {
        long nowNanos = System.nanoTime();
        if (lastSampleNanos > 0L) {
            tps = smooth(tps, nowNanos - lastSampleNanos);
        }
        lastSampleNanos = nowNanos;
    }

    static double smooth(double previous, long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return previous;
        }
        double current = Math.min(20.0D, SAMPLE_TICKS * NANOS_PER_SECOND / elapsedNanos);
        return previous + (current - previous) * SMOOTHING;
    }
}
