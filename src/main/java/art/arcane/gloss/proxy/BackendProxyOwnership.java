package art.arcane.gloss.proxy;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.motd.MotdService;
import art.arcane.gloss.surface.SurfaceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalInt;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendProxyOwnership implements Listener, PluginMessageListener {
    private final Gloss plugin;
    private final Map<UUID, Claim> claims = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private byte[] key;
    private volatile boolean enabled;
    private volatile int lastClaimedMask;
    private int task = -1;
    private int reportedMask;

    public BackendProxyOwnership(Gloss plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        try {
            key = loadKey();
        } catch (IOException | ReflectiveOperationException exception) {
            Gloss.logExceptionStack(true, exception, "Cannot initialize proxy feature ownership");
            return;
        }
        if (key == null) {
            return;
        }
        enabled = true;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, OwnershipProtocol.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, OwnershipProtocol.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = plugin.scheduler().sr(this::tick, 20);
    }

    public void disable() {
        enabled = false;
        if (task != -1) {
            plugin.scheduler().csr(task);
            task = -1;
        }
        HandlerList.unregisterAll(this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, OwnershipProtocol.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, OwnershipProtocol.CHANNEL);
        pending.clear();
        claims.clear();
        reportedMask = 0;
    }

    /** Whether the proxy ownership channel is live, so a feature may still be waiting on a claim. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Whether the last mask a proxy claim carried included connection messages. A lease lapses every
     * fifteen seconds and a player who just joined has none yet, so a feature that must decide before
     * the next handshake asks what the proxy claimed last rather than what it holds this instant. A
     * proxy that never claimed connection messages — or none at all — never answers true.
     */
    public boolean proxyLastClaimedConnections() {
        return (lastClaimedMask & OwnershipProtocol.CONNECTIONS) != 0;
    }

    public boolean ownsTablist(UUID playerId) {
        return owns(playerId, OwnershipProtocol.TABLIST);
    }

    public boolean ownsScoreboard(UUID playerId) {
        return owns(playerId, OwnershipProtocol.SCOREBOARD);
    }

    public boolean ownsSurfaces(UUID playerId) {
        return owns(playerId, OwnershipProtocol.SURFACES);
    }

    public boolean ownsMotd() {
        return ownsAnywhere(OwnershipProtocol.MOTD);
    }

    /**
     * Connection messages are broadcasts, so like the MOTD they are owned server-wide as soon as
     * any connected player carries a live proxy lease for them.
     */
    public boolean ownsConnections() {
        return ownsAnywhere(OwnershipProtocol.CONNECTIONS);
    }

    private boolean ownsAnywhere(int feature) {
        long now = System.currentTimeMillis();
        for (Claim claim : claims.values()) {
            if (claim.expiresAtMillis() > now && (claim.mask() & feature) != 0) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.scheduler().runEntity(player, () -> refresh(player), 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pending.remove(playerId);
        claims.remove(playerId);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled || !OwnershipProtocol.CHANNEL.equals(channel)) {
            return;
        }
        Pending challenge = pending.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (challenge == null || challenge.sentAtMillis() + 5_000L <= now) {
            return;
        }
        OptionalInt mask = OwnershipProtocol.verifyReply(message, challenge.request(), key, now);
        if (mask.isEmpty()) {
            return;
        }
        byte[] acknowledgement = message.clone();
        plugin.scheduler().runEntity(player, () -> {
            if (!enabled || !player.isOnline() || pending.get(player.getUniqueId()) != challenge) {
                return;
            }
            OptionalInt verified = OwnershipProtocol.verifyReply(acknowledgement, challenge.request(), key,
                    System.currentTimeMillis());
            if (verified.isEmpty() || !pending.remove(player.getUniqueId(), challenge)) {
                return;
            }
            boolean changed = apply(player, verified.getAsInt(), System.currentTimeMillis() + 15_000L);
            pending.put(player.getUniqueId(), new Pending(null, challenge.sentAtMillis()));
            byte[] response = Arrays.copyOf(acknowledgement, acknowledgement.length + 1);
            response[response.length - 1] = (byte) (changed ? 1 : 0);
            player.sendPluginMessage(plugin, OwnershipProtocol.CHANNEL, response);
        });
    }

    private boolean owns(UUID playerId, int feature) {
        Claim claim = claims.get(playerId);
        return claim != null && (claim.mask() & feature) != 0;
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().runEntity(player, () -> refresh(player));
        }
        reportOwnership();
    }

    private void reportOwnership() {
        int mask = 0;
        long now = System.currentTimeMillis();
        for (Claim claim : claims.values()) {
            if (claim.expiresAtMillis() > now) {
                mask |= claim.mask();
            }
        }
        if (mask == reportedMask) {
            return;
        }
        int released = reportedMask & ~mask;
        reportedMask = mask;
        Gloss.info("Velocity Gloss ownership: backend features suspended: %s; returned to local configuration: %s.",
                featureNames(mask), featureNames(released));
    }

    private static String featureNames(int mask) {
        StringJoiner names = new StringJoiner(", ");
        names.setEmptyValue("none");
        if ((mask & OwnershipProtocol.TABLIST) != 0) {
            names.add("tablists (proxy-owned players)");
        }
        if ((mask & OwnershipProtocol.SCOREBOARD) != 0) {
            names.add("scoreboards (proxy-owned players)");
        }
        if ((mask & OwnershipProtocol.MOTD) != 0) {
            names.add("MOTD and server links");
        }
        if ((mask & OwnershipProtocol.SURFACES) != 0) {
            names.add("surfaces (proxy-owned players)");
        }
        if ((mask & OwnershipProtocol.CONNECTIONS) != 0) {
            names.add("connection messages");
        }
        return names.toString();
    }

    private void refresh(Player player) {
        if (!enabled || !player.isOnline()) {
            return;
        }
        long now = System.currentTimeMillis();
        Claim claim = claims.get(player.getUniqueId());
        if (claim != null && claim.expiresAtMillis() <= now) {
            apply(player, 0, 0L);
        }
        Pending previous = pending.get(player.getUniqueId());
        if (previous != null && previous.sentAtMillis() + 5_000L > now) {
            return;
        }
        OwnershipProtocol.Request request = OwnershipProtocol.createRequest(player.getUniqueId());
        pending.put(player.getUniqueId(), new Pending(request, now));
        player.sendPluginMessage(plugin, OwnershipProtocol.CHANNEL, OwnershipProtocol.encodeRequest(request));
    }

    private boolean apply(Player player, int mask, long expiresAtMillis) {
        UUID playerId = player.getUniqueId();
        if (mask != 0) {
            lastClaimedMask = mask;
        }
        Claim previous = mask == 0 ? claims.remove(playerId) : claims.put(playerId, new Claim(mask, expiresAtMillis));
        int previousMask = previous == null ? 0 : previous.mask();
        if ((previousMask & OwnershipProtocol.TABLIST) != (mask & OwnershipProtocol.TABLIST)) {
            plugin.tablist().refreshProxyOwnership(player);
        }
        if ((previousMask & OwnershipProtocol.SCOREBOARD) != (mask & OwnershipProtocol.SCOREBOARD)) {
            plugin.boards().refreshProxyOwnership(player);
        }
        if ((previousMask & OwnershipProtocol.SURFACES) != (mask & OwnershipProtocol.SURFACES)) {
            SurfaceService surfaces = plugin.service(SurfaceService.class);
            if (surfaces != null) {
                surfaces.refreshProxyOwnership(player);
            }
        }
        if ((previousMask & OwnershipProtocol.MOTD) != (mask & OwnershipProtocol.MOTD)) {
            MotdService motd = plugin.motd();
            if (motd != null) {
                motd.refreshProxyOwnership();
            }
        }
        return previousMask != mask;
    }

    private byte[] loadKey() throws IOException, ReflectiveOperationException {
        Path customKey = plugin.getDataFolder().toPath().resolve("proxy-ownership.key");
        if (Files.exists(customKey)) {
            byte[] configured = Files.readAllBytes(customKey);
            if (configured.length < 32) {
                throw new IOException("proxy-ownership.key must contain at least 32 bytes");
            }
            return configured;
        }
        Class<?> bridge = Class.forName("art.arcane.gloss.paper.PaperProxyForwardingKey");
        return (byte[]) bridge.getMethod("load").invoke(null);
    }

    private record Claim(int mask, long expiresAtMillis) {
    }

    private record Pending(OwnershipProtocol.Request request, long sentAtMillis) {
    }
}
