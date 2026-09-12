package art.arcane.gloss.condition;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.GlossService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.Objects;

/** Keeps the reflective condition adapters honest across plugin reloads. */
public final class ConditionHooks implements GlossService, Listener {
    private static final String WORLD_GUARD = "WorldGuard";

    private final Gloss plugin;

    public ConditionHooks(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String name() {
        return "condition-hooks";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        ConditionWorldGuard.invalidate();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) {
        if (WORLD_GUARD.equals(event.getPlugin().getName())) {
            ConditionWorldGuard.invalidate();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnable(PluginEnableEvent event) {
        if (WORLD_GUARD.equals(event.getPlugin().getName())) {
            ConditionWorldGuard.invalidate();
        }
    }
}
