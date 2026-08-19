package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class MotdService {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<MotdDoc> registry;
    private PingListener listener;
    private volatile boolean failureLogged;

    public MotdService(Gloss plugin) {
        this.plugin = plugin;
        this.defaults = new ShippedDefaults(MotdDoc.KIND, plugin.getDataFolder(),
            ShippedDocumentCatalog.MOTD.names());
        this.registry = DocumentRegistry.singleFile(MotdDoc.KIND,
            new File(plugin.getDataFolder(), MotdDoc.KIND + ".json"), MotdDoc::parse, MotdDoc::revision);
    }

    public void enable() {
        defaults.extractMissing();
        registry.reload();
        plugin.watchdog().register(MotdDoc.KIND, registry::poll);
        if (!plugin.cfg().motd().enabled()) {
            return;
        }

        listener = new PingListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        plugin.watchdog().unregister(MotdDoc.KIND);
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

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    private MotdDoc doc() {
        GlossDocument<MotdDoc> document = registry.get(MotdDoc.KIND);
        return document == null ? MotdDoc.DEFAULTS : document.value();
    }

    private void handlePing(ServerListPingEvent event) {
        List<MotdDoc.MotdEntry> entries = doc().entries();
        MotdDoc.MotdEntry pick = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        try {
            event.setMotd(plugin.text().renderStatic(pick.joined()));
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
