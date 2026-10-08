package art.arcane.gloss.acceptance;

import art.arcane.gloss.api.GlossAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class GlossIntegrationProbe extends JavaPlugin {
    private static final Map<String, String> METRICS = Map.of(
        "adapt", "adapt.player-sessions",
        "react", "react.sampler.memory-used",
        "iris", "iris.world-count");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player) || arguments.length != 1
            || !arguments[0].matches("[a-z]+")) {
            sender.sendMessage("/glossintegration <phase>");
            return true;
        }
        String phase = arguments[0];
        player.getScheduler().run(this, task -> verify(player, phase), null);
        return true;
    }

    private void verify(Player player, String phase) {
        try {
            GlossAPI api = Bukkit.getServicesManager().load(GlossAPI.class);
            require(api != null && api == GlossAPI.get(), "Public service lookup differs from GlossAPI.get()");
            require(api.getClass().getClassLoader() != getClass().getClassLoader(), "Facade did not cross plugin classloaders");
            require(api.menuIds() != null && api.holograms() != null, "Public collection facade returned null");
            require("GLOSS_API_LINKED".equals(api.filter(player, "GLOSS_API_LINKED")), "Public filter invocation failed");
            for (Map.Entry<String, String> metric : METRICS.entrySet()) {
                verifyMetric(api, player, phase, metric.getKey(), metric.getValue());
            }
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
            getLogger().log(Level.SEVERE, "Integration acceptance failed for " + phase, cause);
            player.sendMessage("GLOSS_INTEGRATION_FAIL phase=" + phase + " type=" + cause.getClass().getSimpleName());
        }
    }

    private void verifyMetric(GlossAPI api, Player player, String phase, String providerId, String key)
        throws ReflectiveOperationException {
        String rendered = api.filter(player, "|metric." + key + "|");
        Object provider = provider(providerId);
        require(provider != null, "Missing provider " + providerId);
        require(provider.getClass().getClassLoader() != GlossAPI.class.getClassLoader(), "Provider shares Gloss classloader");
        Object capabilities = provider.getClass().getMethod("capabilities").invoke(provider);
        require(capabilities instanceof Set<?> values && values.contains("metric-snapshots-v1"), "Missing snapshot capability");
        Object snapshot = provider.getClass().getMethod("snapshotMetrics", Set.class).invoke(provider, Set.of(key));
        long generation = ((Number) snapshot.getClass().getMethod("generation").invoke(snapshot)).longValue();
        long captured = ((Number) snapshot.getClass().getMethod("capturedAtMs").invoke(snapshot)).longValue();
        Map<?, ?> samples = (Map<?, ?>) snapshot.getClass().getMethod("samples").invoke(snapshot);
        Object sample = samples.get(key);
        if (sample == null || !(Boolean) sample.getClass().getMethod("available").invoke(sample)
            || !rendered.matches("-?[0-9]+(?:\\.[0-9]+)?[KMBT]?")) {
            player.sendMessage("GLOSS_INTEGRATION_WAIT phase=" + phase + " provider=" + providerId);
            return;
        }
        int suffix = "KMBT".indexOf(rendered.charAt(rendered.length() - 1));
        double value = suffix < 0 ? Double.parseDouble(rendered)
            : Double.parseDouble(rendered.substring(0, rendered.length() - 1)) * Math.pow(1000D, suffix + 1);
        double direct = ((Number) sample.getClass().getMethod("numericValue").invoke(sample)).doubleValue();
        long sampled = ((Number) sample.getClass().getMethod("sampledAtMs").invoke(sample)).longValue();
        long now = System.currentTimeMillis();
        require(Double.isFinite(value) && Double.isFinite(direct), "Nonfinite metric");
        require(generation > 0 && sampled > 0 && sampled <= captured && captured <= now
            && now - sampled <= 10_000L, "Stale or invalid snapshot timestamps");
        player.sendMessage("GLOSS_INTEGRATION_OK phase=" + phase + " provider=" + providerId
            + " value=" + value + " generation=" + generation + " captured=" + captured + " sampled=" + sampled);
    }

    private Object provider(String id) throws ReflectiveOperationException {
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (!service.getName().endsWith(".integration.IntegrationServiceContract")) {
                continue;
            }
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(service);
            if (registration == null || !registration.getPlugin().isEnabled()) {
                continue;
            }
            Object candidate = registration.getProvider();
            if (id.equals(candidate.getClass().getMethod("pluginId").invoke(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
