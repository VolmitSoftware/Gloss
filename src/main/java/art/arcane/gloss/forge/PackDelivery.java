package art.arcane.gloss.forge;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Hands the built pack to players. The pack is always addressed by its own stable id, so a rebuild
 * replaces the previous download instead of stacking, and every send carries the sha1 the client
 * verifies against: without it a client reuses whatever it cached under that URL.
 */
public final class PackDelivery implements Listener {
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("gloss:forge".getBytes(StandardCharsets.UTF_8));
    public static final String SHA1_PLACEHOLDER = "{sha1}";
    private static final long JOIN_DELAY_TICKS = 1L;

    private final Gloss plugin;
    private final PackNamespace namespace;

    private volatile Settings settings = new Settings("", false, "0.0.0.0", 8085, "", false);
    private volatile PackArtifact artifact;
    private volatile PackListener listener;

    /** The delivery half of {@code [forge]}: where the pack lives and how the client is asked for it. */
    public record Settings(String url, boolean serve, String serveBind, int servePort, String prompt,
                           boolean required) {
        public Settings {
            url = url == null ? "" : url.trim();
            serveBind = serveBind == null || serveBind.isBlank() ? "0.0.0.0" : serveBind.trim();
            prompt = prompt == null ? "" : prompt;
        }
    }

    public PackDelivery(Gloss plugin, PackNamespace namespace) {
        this.plugin = plugin;
        this.namespace = namespace;
    }

    public void enable(Settings next, PackArtifact current) {
        settings = next;
        artifact = current;
        namespace.publish(current == null ? "" : current.sha1Hex());
        if (next.serve()) {
            PackListener started = new PackListener(next.serveBind(), next.servePort());
            if (started.start(current)) {
                listener = started;
            }
        }
        if (plugin != null) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        PackListener current = listener;
        listener = null;
        if (current != null) {
            current.stop();
        }
        artifact = null;
    }

    /** A rebuild retires every viewer's status and re-offers the pack to everyone online. */
    public void publish(PackArtifact current) {
        artifact = current;
        namespace.publish(current == null ? "" : current.sha1Hex());
        PackListener running = listener;
        if (running != null) {
            running.publish(current);
        }
        if (plugin == null || !configured()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendLater(viewer);
        }
    }

    /** Starts or stops the embedded listener at runtime; the config knob is the durable switch. */
    public synchronized boolean serve(boolean on) {
        PackListener current = listener;
        if (on == (current != null)) {
            return false;
        }
        if (on) {
            PackListener started = new PackListener(settings.serveBind(), settings.servePort());
            if (!started.start(artifact)) {
                return false;
            }
            listener = started;
            return true;
        }
        listener = null;
        current.stop();
        return true;
    }

    /** True when a player would have somewhere to download the pack from. */
    public boolean configured() {
        return !url().isEmpty();
    }

    /** The URL sent to clients: the operator's, with {@code {sha1}} filled in, else the listener's. */
    public String url() {
        PackArtifact current = artifact;
        if (current == null) {
            return "";
        }
        String configured = settings.url();
        if (!configured.isEmpty()) {
            return configured.replace(SHA1_PLACEHOLDER, current.sha1Hex());
        }
        PackListener running = listener;
        return running == null ? "" : running.url();
    }

    /** @return true when the request was actually sent to this viewer */
    public boolean send(Player viewer) {
        PackArtifact current = artifact;
        String url = url();
        if (viewer == null || current == null || url.isEmpty()) {
            return false;
        }
        if (plugin != null && plugin.bedrock() != null && plugin.bedrock().isBedrock(viewer.getUniqueId())) {
            return false;
        }
        viewer.setResourcePack(PACK_ID, url, current.sha1(), settings.prompt(), settings.required());
        return true;
    }

    public String describe() {
        PackListener running = listener;
        if (!settings.url().isEmpty()) {
            return "url " + url();
        }
        if (running != null && running.running()) {
            return "listener " + running.url();
        }
        return "off";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sendLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        namespace.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        namespace.record(event.getPlayer().getUniqueId(), event.getStatus().name());
    }

    private void sendLater(Player viewer) {
        if (plugin == null || !configured()) {
            return;
        }
        FoliaScheduler.runEntity(plugin, viewer, () -> send(viewer), JOIN_DELAY_TICKS, () -> {
        });
    }
}
