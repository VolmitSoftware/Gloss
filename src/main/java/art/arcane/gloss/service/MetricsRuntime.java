package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

public final class MetricsRuntime {
    private final Metrics metrics;

    private MetricsRuntime(Metrics metrics) {
        this.metrics = metrics;
    }

    public static MetricsRuntime start(Gloss plugin, int pluginId) {
        if (pluginId <= 0 || !plugin.cfg().metrics()) {
            return null;
        }
        try {
            Metrics metrics = new Metrics(plugin, pluginId);
            metrics.addCustomChart(new SimplePie("holograms_enabled",
                () -> String.valueOf(plugin.cfg().holograms().enabled())));
            metrics.addCustomChart(new SimplePie("boards_enabled",
                () -> String.valueOf(plugin.cfg().boards().enabled())));
            return new MetricsRuntime(metrics);
        } catch (Throwable failure) {
            Gloss.warn("Metrics failed to start: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    public void shutdown() {
        try {
            metrics.shutdown();
        } catch (Throwable failure) {
            Gloss.verbose("Metrics shutdown failed: " + failure.getClass().getSimpleName());
        }
    }
}
