package art.arcane.gloss.integrate;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.PreviewStateProviders;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import art.arcane.volmlib.util.bukkit.Events;
import org.bukkit.Bukkit;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class IntegrationBridgeService {
    public static final String FUNCTION_PREFIX = "metric.";
    public static final long REFERENCE_WINDOW_MS = 60000L;
    public static final int REFERENCE_CAPACITY = 256;

    private static final int NO_TASK = -1;

    private final Gloss plugin;
    private final IntegrationBridge bridge;
    private final ContractDiscovery discovery;
    private final List<String> functions;
    private final List<BridgeStateProvider> providers;

    private Events enableListener;
    private Events disableListener;
    private int taskId;

    public IntegrationBridgeService(Gloss plugin) {
        this.plugin = plugin;
        this.bridge = new IntegrationBridge(
            "gloss",
            plugin.getDescription().getVersion(),
            new MetricReferences(REFERENCE_CAPACITY, REFERENCE_WINDOW_MS)
        );
        this.discovery = new ContractDiscovery();
        this.functions = new ArrayList<>();
        this.providers = new ArrayList<>();
        this.taskId = NO_TASK;
    }

    public void enable() {
        rediscover();
        enableListener = Events.listen(plugin, PluginEnableEvent.class, event -> rediscover());
        disableListener = Events.listen(plugin, PluginDisableEvent.class, event -> rediscover());
        taskId = plugin.scheduler().sr(this::sample, plugin.cfg().integration().sampleIntervalTicks());
    }

    public void disable() {
        if (taskId != NO_TASK) {
            plugin.scheduler().csr(taskId);
            taskId = NO_TASK;
        }
        if (enableListener != null) {
            enableListener.unregister();
            enableListener = null;
        }
        if (disableListener != null) {
            disableListener.unregister();
            disableListener = null;
        }
        detach();
        bridge.clear();
    }

    public void restart(int intervalTicks) {
        if (taskId != NO_TASK) {
            plugin.scheduler().csr(taskId);
        }
        taskId = plugin.scheduler().sr(this::sample, intervalTicks);
    }

    public IntegrationBridge bridge() {
        return bridge;
    }

    private void sample() {
        bridge.sample(System.currentTimeMillis());
    }

    private void rediscover() {
        bridge.adopt(discover());
        detach();
        attach();
    }

    private List<IntegrationServiceContract> discover() {
        List<ContractDiscovery.Candidate> candidates = new ArrayList<>();
        for (Class<?> serviceClass : Bukkit.getServicesManager().getKnownServices()) {
            if (serviceClass == null || !ReflectiveContractAdapter.supports(serviceClass.getName())) {
                continue;
            }
            for (RegisteredServiceProvider<?> registration : registrations(serviceClass)) {
                Plugin owner = registration.getPlugin();
                if (owner == null || !owner.isEnabled() || registration.getProvider() == null) {
                    continue;
                }
                candidates.add(new ContractDiscovery.Candidate(
                    serviceClass.getName(),
                    registration.getProvider(),
                    owner == plugin
                ));
            }
        }
        return discovery.adapt(candidates);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Collection<RegisteredServiceProvider<?>> registrations(Class<?> serviceClass) {
        Collection found = Bukkit.getServicesManager().getRegistrations((Class) serviceClass);
        return found == null ? List.of() : (Collection<RegisteredServiceProvider<?>>) found;
    }

    private void attach() {
        for (String key : bridge.allKeys()) {
            String name = FUNCTION_PREFIX + key;
            plugin.text().registerFunction(name, player -> bridge.render(key, System.currentTimeMillis()));
            functions.add(name);
        }
        for (String namespace : bridge.namespaces()) {
            BridgeStateProvider provider = new BridgeStateProvider(bridge, namespace);
            PreviewStateProviders.register(provider);
            providers.add(provider);
        }
    }

    private void detach() {
        for (String name : functions) {
            plugin.text().unregisterFunction(name);
        }
        functions.clear();
        for (BridgeStateProvider provider : providers) {
            PreviewStateProviders.unregister(provider);
        }
        providers.clear();
    }
}
