package art.arcane.gloss.velocity;

import art.arcane.gloss.proxy.OwnershipProtocol;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ProxyOwnership implements AutoCloseable {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(OwnershipProtocol.CHANNEL);
    private static final long LEASE_MILLIS = 20_000L;
    private static final long ACKNOWLEDGEMENT_MILLIS = 15_000L;

    private final ProxyServer proxy;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Acknowledged> acknowledged = new HashMap<>();
    private byte[] key;

    public ProxyOwnership(ProxyServer proxy, byte[] key) {
        this.proxy = proxy;
        this.key = key == null ? null : key.clone();
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    public static byte[] loadKey(KeySource source) throws IOException {
        Path file = source.directory().resolve("proxy-ownership.key");
        if (Files.exists(file)) {
            byte[] explicitKey = Files.readAllBytes(file);
            if (explicitKey.length < 32) {
                throw new IOException("proxy-ownership.key must contain at least 32 bytes");
            }
            return explicitKey;
        }
        Object configuration = source.proxy().getConfiguration();
        try {
            Object mode = configuration.getClass().getMethod("getPlayerInfoForwardingMode").invoke(configuration);
            if (!(mode instanceof Enum<?> forwarding) || !forwarding.name().equals("MODERN")) {
                return null;
            }
            byte[] automaticKey = (byte[]) configuration.getClass().getMethod("getForwardingSecret").invoke(configuration);
            return automaticKey == null || automaticKey.length == 0 ? null : automaticKey.clone();
        } catch (ReflectiveOperationException failure) {
            source.logger().warn("Gloss cannot read Velocity's forwarding key; automatic backend ownership is unavailable.", failure);
            return null;
        }
    }

    public synchronized int handle(PluginMessageEvent event, int mask) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return 0;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection) || key == null) {
            return 0;
        }
        Player player = connection.getPlayer();
        if (!player.isActive() || player.getCurrentServer().orElse(null) != connection) {
            return 0;
        }
        UUID playerId = player.getUniqueId();
        byte[] data = event.getData();
        long now = System.currentTimeMillis();
        Pending sent = pending.get(playerId);
        if (sent != null && sent.connection() == connection && sent.expiresAtMillis() > now
            && data.length == sent.response().length + 1 && (data[data.length - 1] == 0 || data[data.length - 1] == 1)
            && MessageDigest.isEqual(sent.response(), Arrays.copyOf(data, data.length - 1))) {
            pending.remove(playerId);
            Acknowledged previous = acknowledged.put(playerId, new Acknowledged(connection, sent.mask(), now + ACKNOWLEDGEMENT_MILLIS));
            return data[data.length - 1] == 1 || previous == null || previous.connection() != connection || previous.expiresAtMillis() <= now
                ? sent.mask() : sent.mask() & ~previous.mask();
        }
        OwnershipProtocol.Request request;
        try {
            request = OwnershipProtocol.decodeRequest(data);
        } catch (IllegalArgumentException malformed) {
            return 0;
        }
        if (!request.playerId().equals(playerId)) {
            return 0;
        }
        long expiration = now + LEASE_MILLIS;
        byte[] response = OwnershipProtocol.reply(request, mask, expiration, key);
        pending.put(playerId, new Pending(connection, response.clone(), mask, expiration));
        if (!connection.sendPluginMessage(CHANNEL, response)) {
            pending.remove(playerId);
        }
        return 0;
    }

    public synchronized void setKey(byte[] replacement) {
        if (Arrays.equals(key, replacement)) {
            return;
        }
        key = replacement == null ? null : replacement.clone();
        pending.clear();
        acknowledged.clear();
    }

    public synchronized void forget(UUID playerId) {
        pending.remove(playerId);
        acknowledged.remove(playerId);
    }

    @Override
    public synchronized void close() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        pending.clear();
        acknowledged.clear();
        key = null;
    }

    public record KeySource(ProxyServer proxy, Logger logger, Path directory) { }

    private record Pending(ServerConnection connection, byte[] response, int mask, long expiresAtMillis) { }

    private record Acknowledged(ServerConnection connection, int mask, long expiresAtMillis) { }
}
