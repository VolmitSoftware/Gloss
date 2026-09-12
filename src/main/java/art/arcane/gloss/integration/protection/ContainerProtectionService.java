package art.arcane.gloss.integration.protection;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.GlossContainerPreviewAccessEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Asks every installed claim plugin whether a viewer could open the container it is looking at, so
 * a preview never shows the contents of a chest the viewer cannot open. Deny wins: one provider
 * refusing ends the question. A provider that fails answers no for itself and is logged once,
 * leaving the others voting; uninstalling it restores access.
 */
public final class ContainerProtectionService implements Listener {
    private static final String FALLBACK_NAME = "interact-event";
    private static final List<ProtectionProviderDefinition> TABLE = List.of(
        new ProtectionProviderDefinition("WorldGuard", WorldGuardContainerProtectionProvider::new),
        new ProtectionProviderDefinition("GriefPrevention", GriefPreventionContainerProtectionProvider::new),
        new ProtectionProviderDefinition("Towny", TownyContainerProtectionProvider::new),
        new ProtectionProviderDefinition("Lands", LandsContainerProtectionProvider::new),
        new ProtectionProviderDefinition("PlotSquared", PlotSquaredContainerProtectionProvider::new));

    private final Plugin plugin;
    private final List<ProtectionProviderDefinition> table;
    private final ContainerProtectionProvider fallback;
    private final Consumer<Event> eventDispatcher;
    private final List<Installed> providers = new CopyOnWriteArrayList<>();
    private final Set<String> failuresLogged = ConcurrentHashMap.newKeySet();

    public ContainerProtectionService(Plugin plugin) {
        this(plugin, TABLE,
            new InteractEventContainerProtectionProvider(event -> Bukkit.getPluginManager().callEvent(event)),
            event -> Bukkit.getPluginManager().callEvent(event));
    }

    ContainerProtectionService(Plugin plugin, List<ProtectionProviderDefinition> table,
                               ContainerProtectionProvider fallback, Consumer<Event> eventDispatcher) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.table = List.copyOf(Objects.requireNonNull(table, "table"));
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher");
    }

    public void activate() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (ProtectionProviderDefinition definition : table) {
            Plugin candidate = Bukkit.getPluginManager().getPlugin(definition.pluginName());
            if (candidate != null && candidate.isEnabled()) {
                build(definition, candidate);
            }
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        providers.clear();
        failuresLogged.clear();
    }

    /** The claim plugins currently consulted, in the order they are asked. */
    public List<String> activeProviderNames() {
        List<String> names = new ArrayList<>(providers.size());
        for (Installed installed : providers) {
            names.add(installed.name());
        }
        return List.copyOf(names);
    }

    public boolean canAccess(Player player, Block block) {
        if (!allowed(player, block)) {
            return false;
        }
        GlossContainerPreviewAccessEvent event = new GlossContainerPreviewAccessEvent(player, block);
        eventDispatcher.accept(event);
        return !event.isCancelled();
    }

    public boolean canAccess(Player player, Entity entity) {
        if (!allowed(player, entity)) {
            return false;
        }
        GlossContainerPreviewAccessEvent event = new GlossContainerPreviewAccessEvent(player, entity);
        eventDispatcher.accept(event);
        return !event.isCancelled();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        for (ProtectionProviderDefinition definition : table) {
            if (definition.pluginName().equals(event.getPlugin().getName())) {
                build(definition, event.getPlugin());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        uninstall(event.getPlugin().getName());
    }

    void install(String name, ContainerProtectionProvider provider) {
        uninstall(name);
        providers.add(new Installed(name, provider));
        failuresLogged.remove(name);
    }

    void uninstall(String name) {
        providers.removeIf(installed -> installed.name().equals(name));
        failuresLogged.remove(name);
    }

    void build(ProtectionProviderDefinition definition, Plugin source) {
        String name = definition.pluginName();
        try {
            install(name, definition.factory().create(source));
            Gloss.info("Container previews consult %s.", name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            install(name, DENY);
            if (failuresLogged.add(name)) {
                Gloss.logExceptionStack(true, failure,
                    "Container protection provider %s could not be loaded; previews are denied for its claims.",
                    name);
            }
        }
    }

    /**
     * The fallback fires a synthetic right-click through every other plugin's handlers, so it runs
     * only when no adapter can answer: an installed adapter already speaks for its claims, and a
     * phantom click on every preview refresh is not worth the second opinion.
     */
    private boolean allowed(Player player, Block block) {
        for (Installed installed : providers) {
            if (!ask(installed.name(), installed.provider(), player, block)) {
                return false;
            }
        }
        return !providers.isEmpty() || ask(FALLBACK_NAME, fallback, player, block);
    }

    private boolean allowed(Player player, Entity entity) {
        for (Installed installed : providers) {
            if (!ask(installed.name(), installed.provider(), player, entity)) {
                return false;
            }
        }
        return !providers.isEmpty() || ask(FALLBACK_NAME, fallback, player, entity);
    }

    private boolean ask(String name, ContainerProtectionProvider provider, Player player, Block block) {
        try {
            return provider.canAccess(player, block);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(name, failure);
            return false;
        }
    }

    private boolean ask(String name, ContainerProtectionProvider provider, Player player, Entity entity) {
        try {
            return provider.canAccess(player, entity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logFailure(name, failure);
            return false;
        }
    }

    private void logFailure(String name, Throwable failure) {
        if (failuresLogged.add(name)) {
            Gloss.logExceptionStack(true, failure,
                "Container protection provider %s failed; it denies every preview until it recovers.", name);
        }
    }

    /**
     * Stands in for an adapter that could not be built. A claim plugin Gloss cannot read is not a
     * claim plugin that stops mattering: it keeps refusing under its own name until it is fixed or
     * uninstalled, which is what the failure log already promises.
     */
    private static final ContainerProtectionProvider DENY = new ContainerProtectionProvider() {
        @Override
        public boolean canAccess(Player player, Block block) {
            return false;
        }

        @Override
        public boolean canAccess(Player player, Entity entity) {
            return false;
        }
    };

    private record Installed(String name, ContainerProtectionProvider provider) {
    }
}
