package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The personalised text refresh is keyed on the PacketEvents spawn/destroy interception, so the
 * varint peek that replaced the wrapper decode is driven here through the same entry point the
 * listener uses, rather than through a direct {@code displayTrackingChanged} call.
 */
class HologramTrackingObserverTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle viewer;

    @BeforeEach
    void setUp() {
        PacketEvents.setAPI(new TestPacketEventsApi());
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        viewer = harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
        PacketEvents.setAPI(null);
    }

    @Test
    void anInterceptedSpawnPacketRefreshesThatViewersPersonalizedText() {
        PersistentHologram hologram = personalized("h-observe-spawn");
        int settled = settle(hologram);
        FakeBuffer buffer = new FakeBuffer(varInts(harness.onlySpawned(world).proxy.getEntityId()));

        observe(buffer, true);

        assertEquals(settled + 1, drive(hologram),
            "the spawn peek must reach the re-track path and re-send the personalized text");
        assertEquals(0, buffer.readerIndex, "the peek must leave the buffer where it found it");
    }

    @Test
    void anInterceptedDestroyPacketStopsWritingToThatViewer() {
        PersistentHologram hologram = personalized("h-observe-destroy");
        int settled = settle(hologram);
        int entityId = harness.onlySpawned(world).proxy.getEntityId();
        FakeBuffer buffer = new FakeBuffer(varInts(3, 4096, entityId, 7));

        observe(buffer, false);
        hologram.setLines(List.of("Changed %player_name%"));

        assertEquals(settled, drive(hologram),
            "a destroy id read from the middle of the varint array must stop writing to that viewer");
        assertEquals(0, buffer.readerIndex, "the peek must leave the buffer where it found it");
    }

    @Test
    void aDestroyForOtherEntitiesLeavesTheViewerWriteable() {
        PersistentHologram hologram = personalized("h-observe-destroy-miss");
        int settled = settle(hologram);
        int entityId = harness.onlySpawned(world).proxy.getEntityId();

        observe(new FakeBuffer(varInts(3, 4096, entityId + 2, 7)), false);
        hologram.setLines(List.of("Changed %player_name%"));

        assertEquals(settled + 1, drive(hologram),
            "a destroy that never names this display must not stop its viewer");
    }

    @Test
    void aTruncatedDestroyArrayStopsAtTheEndOfTheBuffer() {
        PersistentHologram hologram = personalized("h-observe-truncated");
        int settled = settle(hologram);
        int entityId = harness.onlySpawned(world).proxy.getEntityId();

        observe(new FakeBuffer(varInts(64, entityId)), false);
        hologram.setLines(List.of("Changed %player_name%"));

        assertEquals(settled, drive(hologram),
            "a count larger than the payload must read what is there and stop, not over-read");
    }

    private PersistentHologram personalized(String id) {
        PersistentHologram hologram = harness.persistent(id, harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("Hello %player_name%"));
        return hologram;
    }

    /** Drives until the personalized text has been sent once, then pins that it stays quiet. */
    private int settle(PersistentHologram hologram) {
        drive(hologram);
        int settled = harness.sender.sent.size();
        assertEquals(settled, drive(hologram),
            "an unchanged drive inside the refresh window must not resend personalized metadata");
        return settled;
    }

    private int drive(PersistentHologram hologram) {
        hologram.update();
        harness.drainDelayed();
        harness.animator.pass(System.currentTimeMillis());
        return harness.sender.sent.size();
    }

    private void observe(FakeBuffer buffer, boolean spawn) {
        harness.service.readTrackedEntityIds(buffer, spawn, viewer.proxy, viewer.uuid);
        harness.drainDelayed();
    }

    private static byte[] varInts(int... values) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value : values) {
            int remaining = value;
            while ((remaining & ~0x7F) != 0) {
                bytes.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            bytes.write(remaining);
        }
        return bytes.toByteArray();
    }

    private static final class FakeBuffer {
        private final byte[] bytes;
        private int readerIndex;

        private FakeBuffer(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private static final class TestPacketEventsApi extends PacketEventsAPI<Object> {
        private final ByteBufOperator byteBufs = (ByteBufOperator) Proxy.newProxyInstance(
            ByteBufOperator.class.getClassLoader(), new Class<?>[]{ByteBufOperator.class},
            (proxy, method, args) -> {
                FakeBuffer buffer = (FakeBuffer) args[0];
                return switch (method.getName()) {
                    case "isReadable" -> buffer.readerIndex < buffer.bytes.length;
                    case "readableBytes" -> buffer.bytes.length - buffer.readerIndex;
                    case "readByte" -> buffer.bytes[buffer.readerIndex++];
                    case "readerIndex" -> {
                        if (args.length == 1) {
                            yield buffer.readerIndex;
                        }
                        buffer.readerIndex = (Integer) args[1];
                        yield buffer;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });

        private final NettyManager netty = new NettyManager() {
            @Override
            public ChannelOperator getChannelOperator() {
                return null;
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
            return null;
        }

        @Override
        public PlayerManager getPlayerManager() {
            return null;
        }

        @Override
        public NettyManager getNettyManager() {
            return netty;
        }

        @Override
        public ChannelInjector getInjector() {
            return null;
        }
    }
}
