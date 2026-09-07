package art.arcane.gloss.util.common;

import art.arcane.gloss.service.GlossTelemetry;
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
import com.github.retrooper.packetevents.protocol.ProtocolVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the fan-out shape of {@link PacketUtils}: one flush per recipient instead of one per packet,
 * and one encode per broadcast instead of one per recipient.
 */
class PacketUtilsBroadcastTest {
    private TestPacketEventsApi api;

    @BeforeEach
    void installApi() {
        api = new TestPacketEventsApi();
        PacketEvents.setAPI(api);
        GlossTelemetry.clear();
    }

    @AfterEach
    void clearApi() {
        PacketEvents.setAPI(null);
        GlossTelemetry.clear();
    }

    @Test
    void sendFlushesOncePerRecipientRatherThanOncePerPacket() {
        List<Player> players = List.of(api.player(), api.player());
        List<PacketWrapper<?>> packets = List.of(packet(1), packet(2), packet(3));

        PacketUtils.send(players, packets);

        assertEquals(6, api.protocol.encodes.size(), "send still encodes per recipient per packet");
        assertEquals(6, api.protocol.writes.size());
        assertEquals(2, api.channel.flushes.size(), "one flush per recipient, not per packet");
    }

    @Test
    void broadcastEncodesOnceAndRetainsOneDuplicatePerRecipient() {
        List<Player> players = List.of(api.player(), api.player(), api.player());

        PacketUtils.broadcast(players, packet(7), () -> true);

        assertEquals(1, api.protocol.encodes.size(), "one encode for the whole audience");
        assertEquals(3, api.protocol.writes.size());
        assertEquals(3, api.channel.flushes.size());
        List<FakeBuffer> written = api.protocol.writtenBuffers();
        assertEquals(3, written.size());
        assertNotSame(written.get(0), written.get(1));
        assertNotSame(written.get(1), written.get(2));
        for (FakeBuffer duplicate : written) {
            assertSame(api.buffers.getFirst(), duplicate.origin, "every recipient shares one encode");
        }
        assertEquals(List.of(api.buffers.getFirst()), api.released,
            "the encoded buffer is released exactly once, the duplicates travel with the writes");
        assertEquals(3, api.buffers.getFirst().refCount.get());
    }

    @Test
    void broadcastCountsOnePacketPerRecipient() {
        List<Player> players = List.of(api.player(), api.player(), api.player());
        GlossTelemetry.packetsPerSecond(1L);

        PacketUtils.broadcast(players, packet(7), () -> true);

        assertEquals(3.0D, GlossTelemetry.packetsPerSecond(1001L), 0.0001D);
    }

    @Test
    void broadcastStopsWhenTheSourceIsRetiredMidFanOut() {
        List<Player> players = List.of(api.player(), api.player(), api.player());
        AtomicInteger polls = new AtomicInteger();
        GlossTelemetry.packetsPerSecond(1L);

        PacketUtils.broadcast(players, packet(7), () -> polls.getAndIncrement() < 2);

        assertEquals(1, api.protocol.writes.size(), "the retired fan-out stops after the first write");
        assertEquals(1, api.channel.flushes.size());
        assertEquals(List.of(api.buffers.getFirst()), api.released,
            "an abandoned fan-out still releases its encode");
        assertEquals(1.0D, GlossTelemetry.packetsPerSecond(1001L), 0.0001D,
            "an abandoned fan-out counts what it sent, not what it intended to send");
    }

    @Test
    void aFailedRecipientWriteReleasesItsDuplicate() {
        List<Player> players = List.of(api.player(), api.player());
        api.protocol.failOnWrite = true;

        assertThrows(IllegalStateException.class,
            () -> PacketUtils.broadcast(players, packet(7), () -> true));

        FakeBuffer encoded = api.buffers.getFirst();
        assertEquals(0, encoded.refCount.get(),
            "a throw between the retain and the write must not strand the duplicate");
    }

    private static PacketWrapper<?> packet(int entityId) {
        return new WrapperPlayServerDestroyEntities(entityId);
    }

    private static final class FakeBuffer {
        private final FakeBuffer origin;
        private final AtomicInteger refCount;

        private FakeBuffer() {
            this.origin = this;
            this.refCount = new AtomicInteger(1);
        }

        private FakeBuffer(FakeBuffer parent) {
            this.origin = parent.origin;
            this.refCount = parent.refCount;
        }
    }

    private final class RecordingProtocolManager implements ProtocolManager {
        private final List<PacketWrapper<?>> encodes = new ArrayList<>();
        private final List<Object> writes = new ArrayList<>();
        private boolean failOnWrite;

        private List<FakeBuffer> writtenBuffers() {
            List<FakeBuffer> buffers = new ArrayList<>(writes.size());
            for (Object write : writes) {
                buffers.add((FakeBuffer) write);
            }
            return buffers;
        }

        @Override
        public Object[] transformWrappers(PacketWrapper<?> wrapper, Object channel, boolean outgoing) {
            encodes.add(wrapper);
            FakeBuffer buffer = new FakeBuffer();
            api.buffers.add(buffer);
            return new Object[]{buffer};
        }

        @Override
        public ProtocolVersion getPlatformVersion() {
            return ProtocolVersion.UNKNOWN;
        }

        @Override
        public void sendPacket(Object channel, Object byteBuf) {
            writes.add(byteBuf);
        }

        @Override
        public void sendPacketSilently(Object channel, Object byteBuf) {
            writes.add(byteBuf);
        }

        @Override
        public void writePacket(Object channel, Object byteBuf) {
            if (failOnWrite) {
                throw new IllegalStateException("channel write failed");
            }
            writes.add(byteBuf);
        }

        @Override
        public void writePacketSilently(Object channel, Object byteBuf) {
            writes.add(byteBuf);
        }

        @Override
        public void receivePacket(Object channel, Object byteBuf) {
        }

        @Override
        public void receivePacketSilently(Object channel, Object byteBuf) {
        }

        @Override
        public ClientVersion getClientVersion(Object channel) {
            return ClientVersion.UNKNOWN;
        }

        @Override
        public User getUser(Object channel) {
            return null;
        }
    }

    private static final class RecordingChannelOperator implements ChannelOperator {
        private final List<Object> flushes = new ArrayList<>();

        @Override
        public java.net.SocketAddress remoteAddress(Object channel) {
            return null;
        }

        @Override
        public java.net.SocketAddress localAddress(Object channel) {
            return null;
        }

        @Override
        public boolean isOpen(Object channel) {
            return true;
        }

        @Override
        public Object close(Object channel) {
            return null;
        }

        @Override
        public Object write(Object channel, Object buffer) {
            return null;
        }

        @Override
        public Object flush(Object channel) {
            flushes.add(channel);
            return null;
        }

        @Override
        public Object writeAndFlush(Object channel, Object buffer) {
            return null;
        }

        @Override
        public Object fireChannelRead(Object channel, Object buffer) {
            return null;
        }

        @Override
        public Object writeInContext(Object channel, String ctx, Object buffer) {
            return null;
        }

        @Override
        public Object flushInContext(Object channel, String ctx) {
            return null;
        }

        @Override
        public Object writeAndFlushInContext(Object channel, String ctx, Object buffer) {
            return null;
        }

        @Override
        public Object fireChannelReadInContext(Object channel, String ctx, Object buffer) {
            return null;
        }

        @Override
        public List<String> pipelineHandlerNames(Object channel) {
            return List.of();
        }

        @Override
        public Object getPipeline(Object channel) {
            return channel;
        }

        @Override
        public Object getPipelineHandler(Object channel, String name) {
            return null;
        }

        @Override
        public Object getPipelineContext(Object channel, String handlerName) {
            return null;
        }

        @Override
        public Object pooledByteBuf(Object channel) {
            return new FakeBuffer();
        }

        @Override
        public void runInEventLoop(Object channel, Runnable runnable) {
            runnable.run();
        }
    }

    private final class TestPacketEventsApi extends PacketEventsAPI<Object> {
        private final RecordingProtocolManager protocol = new RecordingProtocolManager();
        private final RecordingChannelOperator channel = new RecordingChannelOperator();
        private final List<FakeBuffer> buffers = new ArrayList<>();
        private final List<FakeBuffer> released = new ArrayList<>();
        private final Map<Player, Object> channels = new IdentityHashMap<>();
        private final ByteBufOperator byteBufs = byteBufOperator();
        private final PlayerManager players = playerManager();
        private final ChannelInjector injector = injector();

        private Player player() {
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> UUID.randomUUID();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            channels.put(player, new Object());
            return player;
        }

        private ByteBufOperator byteBufOperator() {
            return (ByteBufOperator) Proxy.newProxyInstance(ByteBufOperator.class.getClassLoader(),
                new Class<?>[]{ByteBufOperator.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "retainedDuplicate" -> {
                        FakeBuffer source = (FakeBuffer) args[0];
                        source.refCount.incrementAndGet();
                        yield new FakeBuffer(source);
                    }
                    case "release" -> {
                        FakeBuffer source = (FakeBuffer) args[0];
                        released.add(source);
                        yield source.refCount.decrementAndGet() == 0;
                    }
                    case "refCnt" -> ((FakeBuffer) args[0]).refCount.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private PlayerManager playerManager() {
            return (PlayerManager) Proxy.newProxyInstance(PlayerManager.class.getClassLoader(),
                new Class<?>[]{PlayerManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getChannel" -> channels.get((Player) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private ChannelInjector injector() {
            return (ChannelInjector) Proxy.newProxyInstance(ChannelInjector.class.getClassLoader(),
                new Class<?>[]{ChannelInjector.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isProxy" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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
            return new NettyManager() {
                @Override
                public ChannelOperator getChannelOperator() {
                    return channel;
                }

                @Override
                public ByteBufOperator getByteBufOperator() {
                    return byteBufs;
                }

                @Override
                public ByteBufAllocationOperator getByteBufAllocationOperator() {
                    return null;
                }
            };
        }

        @Override
        public ChannelInjector getInjector() {
            return injector;
        }
    }
}
