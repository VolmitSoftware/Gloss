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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands the built pack to players. Each content hash has its own request id, and a replacement removes
 * the previous request before sending its successor, and every send carries the sha1 the client
 * verifies against: without it a client reuses whatever it cached under that URL.
 */
public final class PackDelivery implements Listener {
    public static final String SHA1_PLACEHOLDER = "{sha1}";
    private static final long JOIN_DELAY_TICKS = 1L;

    private final Gloss plugin;
    private final PackNamespace namespace;
    private final PackArtifactStore artifacts;
    private final Map<UUID, Request> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Request>> offers = new ConcurrentHashMap<>();

    private volatile Settings settings = new Settings("", false, "0.0.0.0", 8085, "", false, 4, 32);
    private volatile PackArtifact artifact;
    private volatile PackListener listener;
    private PackArtifactStore.Lease currentLease;

    /** The delivery half of {@code [forge]}: where the pack lives and how the client is asked for it. */
    public record Settings(String url, boolean serve, String serveBind, int servePort, String prompt,
                           boolean required, int listenerThreads, int listenerBacklog) {
        public Settings {
            url = url == null ? "" : url.trim();
            serveBind = serveBind == null || serveBind.isBlank() ? "0.0.0.0" : serveBind.trim();
            prompt = prompt == null ? "" : prompt;
        }
    }

    public PackDelivery(Gloss plugin, PackNamespace namespace) {
        this(new Options(plugin, namespace, new PackArtifactStore()));
    }

    public PackDelivery(Options options) {
        Gloss plugin = options.plugin();
        PackNamespace namespace = options.namespace();
        this.plugin = plugin;
        this.namespace = namespace;
        this.artifacts = options.artifacts();
    }

    public record Options(Gloss plugin, PackNamespace namespace, PackArtifactStore artifacts) {
    }

    public synchronized void enable(Settings next, PackArtifact current) {
        settings = next;
        replaceCurrent(current);
        namespace.publish(current == null ? "" : current.sha1Hex());
        if (next.serve()) {
            PackListener started = new PackListener(new PackListener.Options(next.serveBind(), next.servePort(), next.listenerThreads(), next.listenerBacklog()), artifacts);
            if (started.start(current)) {
                listener = started;
            }
        }
        if (plugin != null) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    public synchronized void disable() {
        HandlerList.unregisterAll(this);
        PackListener current = listener;
        listener = null;
        if (current != null) {
            current.stop();
        }
        replaceCurrent(null);
        for (Map<UUID, Request> pending : offers.values()) {
            for (Request request : pending.values()) {
                request.lease().close();
            }
        }
        offers.clear();
        requested.clear();
    }

    public synchronized void reconfigure(Settings next) {
        boolean changed = settings.serve() != next.serve() || !settings.serveBind().equals(next.serveBind())
            || settings.servePort() != next.servePort() || settings.listenerThreads() != next.listenerThreads()
            || settings.listenerBacklog() != next.listenerBacklog();
        settings = next;
        if (!changed) {
            return;
        }
        PackListener previous = listener;
        listener = null;
        if (previous != null) {
            previous.stop();
        }
        if (next.serve()) {
            serve(true);
        }
    }

    /** A rebuild retires every viewer's status and re-offers the pack to everyone online. */
    public synchronized void publish(PackArtifact current) {
        replaceCurrent(current);
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
            PackListener started = new PackListener(new PackListener.Options(settings.serveBind(), settings.servePort(), settings.listenerThreads(), settings.listenerBacklog()), artifacts);
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
        return url(artifact);
    }

    private String url(PackArtifact current) {
        if (current == null) {
            return "";
        }
        String configured = settings.url();
        if (!configured.isEmpty()) {
            return configured.replace(SHA1_PLACEHOLDER, current.sha1Hex());
        }
        PackListener running = listener;
        return running == null ? "" : running.url(current);
    }

    /** @return true when the request was actually sent to this viewer */
    public synchronized boolean send(Player viewer) {
        PackArtifact current = artifact;
        String url = url(current);
        if (viewer == null || current == null || url.isEmpty()) {
            return false;
        }
        if (plugin != null && plugin.bedrock() != null && plugin.bedrock().isBedrock(viewer.getUniqueId())) {
            return false;
        }
        UUID playerId = viewer.getUniqueId();
        Request request = new Request(id(current), current.sha1Hex(), artifacts.retain(current));
        Request previous = requested.put(playerId, request);
        Map<UUID, Request> pending = offers.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        Request repeated = pending.put(request.id(), request);
        if (repeated != null) {
            repeated.lease().close();
        }
        namespace.forget(playerId);
        try {
            if (previous != null && !previous.id().equals(request.id())) {
                viewer.removeResourcePack(previous.id());
            }
            viewer.setResourcePack(request.id(), url, current.sha1(), settings.prompt(), settings.required());
        } catch (RuntimeException failure) {
            requested.remove(playerId, request);
            pending.remove(request.id(), request);
            request.lease().close();
            throw failure;
        }
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
    public synchronized void onQuit(PlayerQuitEvent event) {
        namespace.forget(event.getPlayer().getUniqueId());
        requested.remove(event.getPlayer().getUniqueId());
        Map<UUID, Request> pending = offers.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            for (Request request : pending.values()) {
                request.lease().close();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void onStatus(PlayerResourcePackStatusEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String status = event.getStatus().name();
        Map<UUID, Request> pending = offers.get(playerId);
        if (pending != null && !status.equals("ACCEPTED") && !status.equals("DOWNLOADED")) {
            Request completed = pending.remove(event.getID());
            if (completed != null) {
                completed.lease().close();
            }
            if (pending.isEmpty()) {
                offers.remove(playerId, pending);
            }
        }
        Request request = requested.get(playerId);
        PackArtifact current = artifact;
        if (request == null || !request.id().equals(event.getID())) {
            return;
        }
        if (current == null || !request.sha1().equals(current.sha1Hex())) {
            return;
        }
        namespace.record(playerId, event.getStatus().name(), request.sha1());
        if (plugin != null) {
            FoliaScheduler.runGlobal(plugin, () -> {
                if (plugin.getSessionManager() != null) {
                    plugin.getSessionManager().refreshVisuals();
                }
                if (plugin.getPanelRuntime() != null) {
                    plugin.getPanelRuntime().refreshVisuals();
                }
            });
        }
    }

    static UUID id(PackArtifact artifact) {
        return UUID.nameUUIDFromBytes(("gloss:forge:" + artifact.sha1Hex()).getBytes(StandardCharsets.UTF_8));
    }

    private record Request(UUID id, String sha1, PackArtifactStore.Lease lease) {
    }

    private void replaceCurrent(PackArtifact current) {
        PackArtifactStore.Lease previous = currentLease;
        currentLease = artifacts.retain(current);
        artifact = current;
        if (previous != null) {
            previous.close();
        }
    }

    private void sendLater(Player viewer) {
        if (plugin == null || !configured()) {
            return;
        }
        FoliaScheduler.runEntity(plugin, viewer, () -> send(viewer), JOIN_DELAY_TICKS, () -> {
        });
    }
}
