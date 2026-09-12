package art.arcane.gloss.packets;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperator;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A packetevents API with just enough behind it to construct wrappers and record what was sent.
 * {@link PacketWrapper}'s constructor reads the server version off the global API, so a test that
 * builds a packet at all needs one installed.
 */
public final class PacketEventsStub extends PacketEventsAPI<Object> {
    private final List<Sent> sent = new ArrayList<>();
    private final Map<Player, ClientVersion> versions = new IdentityHashMap<>();
    private final Map<Player, Channel> channels = new IdentityHashMap<>();
    private final PlayerManager players = playerManager();
    private final ProtocolManager protocol = protocolManager();
    private final NettyManager netty = nettyManager();

    public record Sent(Player viewer, PacketWrapper<?> packet) {
    }

    private record Channel(Player viewer) {
    }

    /** Installs a fresh stub as the global API and returns it. */
    public static PacketEventsStub install() {
        PacketEventsStub stub = new PacketEventsStub();
        PacketEvents.setAPI(stub);
        return stub;
    }

    public static void uninstall() {
        PacketEvents.setAPI(null);
    }

    public void clientVersion(Player viewer, ClientVersion version) {
        versions.put(viewer, version);
    }

    public void record(Player viewer, PacketWrapper<?> packet) {
        sent.add(new Sent(viewer, packet));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public void init() {
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public Object getPlugin() {
        return this;
    }

    @Override
    public ServerManager getServerManager() {
        return () -> ServerVersion.V_26_1_2;
    }

    @Override
    public ProtocolManager getProtocolManager() {
        return protocol;
    }

    @Override
    public PlayerManager getPlayerManager() {
        return players;
    }

    @Override
    public NettyManager getNettyManager() {
        return netty;
    }

    @Override
    public ChannelInjector getInjector() {
        throw new UnsupportedOperationException("getInjector");
    }

    /** Packets sent to this viewer, in order, filtered to one wrapper type. */
    public <T extends PacketWrapper<?>> List<T> sentTo(Player viewer, Class<T> type) {
        List<T> matched = new ArrayList<>();
        for (Sent entry : sent) {
            if (entry.viewer() == viewer && type.isInstance(entry.packet())) {
                matched.add(type.cast(entry.packet()));
            }
        }
        return List.copyOf(matched);
    }

    private ProtocolManager protocolManager() {
        return (ProtocolManager) Proxy.newProxyInstance(ProtocolManager.class.getClassLoader(),
            new Class<?>[]{ProtocolManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("writePacket") && args[1] instanceof PacketWrapper<?> packet) {
                    record(((Channel) args[0]).viewer(), packet);
                    return null;
                }
                if (method.getName().equals("getPlatformVersion")) {
                    return com.github.retrooper.packetevents.protocol.ProtocolVersion.UNKNOWN;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private NettyManager nettyManager() {
        ChannelOperator operator = (ChannelOperator) Proxy.newProxyInstance(
            ChannelOperator.class.getClassLoader(), new Class<?>[]{ChannelOperator.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "flush" -> args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return new NettyManager() {
            @Override
            public ChannelOperator getChannelOperator() {
                return operator;
            }

            @Override
            public ByteBufOperator getByteBufOperator() {
                throw new UnsupportedOperationException("getByteBufOperator");
            }

            @Override
            public ByteBufAllocationOperator getByteBufAllocationOperator() {
                throw new UnsupportedOperationException("getByteBufAllocationOperator");
            }
        };
    }

    private PlayerManager playerManager() {
        return (PlayerManager) Proxy.newProxyInstance(PlayerManager.class.getClassLoader(),
            new Class<?>[]{PlayerManager.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getClientVersion" -> versions.get((Player) args[0]);
                case "getChannel" -> channels.computeIfAbsent((Player) args[0], viewer -> new Channel(viewer));
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
