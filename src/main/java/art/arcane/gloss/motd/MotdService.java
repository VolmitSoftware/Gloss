package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class MotdService {
    private final Gloss plugin;
    private PingListener listener;
    private volatile boolean failureLogged;

    public MotdService(Gloss plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        GlossConfig.Motd config = plugin.cfg().motd();
        if (!config.enabled() || config.texts().isEmpty()) {
            return;
        }

        listener = new PingListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        if (listener == null) {
            return;
        }

        HandlerList.unregisterAll(listener);
        listener = null;
    }

    public void reload() {
        disable();
        failureLogged = false;
        enable();
    }

    private void handlePing(ServerListPingEvent event) {
        List<String> texts = plugin.cfg().motd().texts();
        if (texts.isEmpty()) {
            return;
        }

        String pick = texts.get(ThreadLocalRandom.current().nextInt(texts.size())).replace("\\n", "\n");
        try {
            event.setMotd(plugin.text().renderStatic(pick));
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                Gloss.warn("MOTD render failed: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            }
        }
    }

    private final class PingListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void on(ServerListPingEvent event) {
            handlePing(event);
        }
    }
}
