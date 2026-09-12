package art.arcane.gloss.motion;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformStreamerTest {
    private record Sent(List<Player> viewers, PacketWrapper<?> packet, BooleanSupplier live) {
    }

    private static final class RecordingSink implements TransformStreamer.Sink {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(List<Player> viewers, PacketWrapper<?> packet, BooleanSupplier live) {
            sent.add(new Sent(List.copyOf(viewers), packet, live));
        }

        private int recipientCount() {
            int recipients = 0;
            for (Sent frame : sent) {
                recipients += frame.viewers().size();
            }
            return recipients;
        }
    }

    private static final class FakeSource implements TransformFrameSource {
        private final List<Player> viewers;
        private final int fps;
        private final int packetsPerFrame;
        private final AtomicInteger composes = new AtomicInteger();
        private final AtomicBoolean live = new AtomicBoolean(true);

        private FakeSource(List<Player> viewers, int fps, int packetsPerFrame) {
            this.viewers = viewers;
            this.fps = fps;
            this.packetsPerFrame = packetsPerFrame;
        }

        @Override
        public List<PacketWrapper<?>> compose(long nowMs) {
            composes.incrementAndGet();
            List<PacketWrapper<?>> packets = new ArrayList<>(packetsPerFrame);
            for (int index = 0; index < packetsPerFrame; index++) {
                packets.add(new WrapperPlayServerDestroyEntities(index));
            }
            return packets;
        }

        @Override
        public List<Player> viewers() {
            return viewers;
        }

        @Override
        public boolean live() {
            return live.get();
        }

        @Override
        public int fps() {
            return fps;
        }
    }

    @BeforeAll
    static void installPacketEventsApi() {
        PacketEvents.setAPI(new StubPacketEventsApi());
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    private static GlossConfig config(int packetBudget, int maxMotionFps) {
        GlossConfigFile file = new GlossConfigFile();
        file.rigs.transformPacketBudget = packetBudget;
        file.rigs.maxMotionFps = maxMotionFps;
        file.normalize();
        return GlossConfig.from(file);
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static TransformStreamer streamer(GlossConfig config, RecordingSink sink, AtomicLong clock) {
        return new TransformStreamer(() -> config, sink, clock::get, TransformStreamer.IDLE_EXIT_MILLIS);
    }

    @Test
    void perSourceFpsGovernsHowOftenAFrameIsComposed() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(20_000, 120), sink, new AtomicLong());
        FakeSource source = new FakeSource(List.of(player()), 20, 1);
        streamer.publish("rig:a", source);

        assertEquals(1, streamer.pass(0L));
        assertEquals(0, streamer.pass(20L));
        assertEquals(0, streamer.pass(49L));
        assertEquals(1, streamer.pass(50L));
        assertEquals(2, source.composes.get());
        assertEquals(2, sink.sent.size());
    }

    @Test
    void configuredMaxMotionFpsCapsASourceThatAsksForMore() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(20_000, 10), sink, new AtomicLong());
        streamer.publish("rig:a", new FakeSource(List.of(player()), 120, 1));

        assertEquals(1, streamer.pass(0L));
        assertEquals(0, streamer.pass(50L));
        assertEquals(1, streamer.pass(100L));
    }

    @Test
    void packetBudgetThrottlingLowersTheEffectiveFps() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(100, 120), sink, new AtomicLong());
        streamer.publish("rig:a", new FakeSource(List.of(player(), player()), 100, 1));

        int sendsInFirstSecond = 0;
        for (long nowMs = 0L; nowMs < 1000L; nowMs += 10L) {
            sendsInFirstSecond += streamer.pass(nowMs);
        }
        assertEquals(50, sendsInFirstSecond, "two recipients per frame against a 100 recipient window");
        assertEquals(100, sink.recipientCount());
        assertEquals(1, streamer.pass(1000L), "the window slides and frames resume");
    }

    @Test
    void everyPartPacketOfAFrameCountsAgainstTheBudget() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(100, 120), sink, new AtomicLong());
        streamer.publish("rig:a", new FakeSource(List.of(player()), 100, 10));

        int sends = 0;
        for (long nowMs = 0L; nowMs < 1000L; nowMs += 10L) {
            sends += streamer.pass(nowMs);
        }
        assertEquals(100, sends, "ten packets per frame, ten frames fit the window");
    }

    @Test
    void aFrameLargerThanTheWholeWindowStillSendsWhenTheWindowIsEmpty() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(100, 120), sink, new AtomicLong());
        List<Player> viewers = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            viewers.add(player());
        }
        streamer.publish("rig:a", new FakeSource(viewers, 100, 1));

        assertEquals(1, streamer.pass(0L));
        assertEquals(0, streamer.pass(10L));
        assertEquals(0, streamer.pass(999L));
        assertEquals(1, streamer.pass(1000L));
    }

    @Test
    void removedSourcesStopAndTheirInFlightLivenessTurnsFalse() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(20_000, 120), sink, new AtomicLong());
        streamer.publish("rig:a", new FakeSource(List.of(player()), 60, 1));

        assertEquals(1, streamer.pass(0L));
        assertTrue(sink.sent.getFirst().live().getAsBoolean());
        streamer.remove("rig:a");
        assertFalse(sink.sent.getFirst().live().getAsBoolean());
        assertEquals(0, streamer.pass(100L));
        assertEquals(0, streamer.sourceCount());
    }

    @Test
    void deadOrViewerlessSourcesAreNotComposed() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(20_000, 120), sink, new AtomicLong());
        FakeSource dead = new FakeSource(List.of(player()), 60, 1);
        dead.live.set(false);
        FakeSource empty = new FakeSource(List.of(), 60, 1);
        streamer.publish("rig:dead", dead);
        streamer.publish("rig:empty", empty);

        assertEquals(0, streamer.pass(0L));
        assertEquals(0, dead.composes.get());
        assertEquals(0, empty.composes.get());
    }

    @Test
    void republishingAKeyRetiresThePreviousPublication() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(20_000, 120), sink, new AtomicLong());
        streamer.publish("rig:a", new FakeSource(List.of(player()), 60, 1));
        assertEquals(1, streamer.pass(0L));
        BooleanSupplier first = sink.sent.getFirst().live();
        streamer.publish("rig:a", new FakeSource(List.of(player()), 60, 1));

        assertFalse(first.getAsBoolean());
        assertEquals(1, streamer.pass(0L));
        assertTrue(sink.sent.get(1).live().getAsBoolean());
    }

    @Test
    void workerExitsAfterTheIdleWindowAndRestartsOnPublish() throws Exception {
        RecordingSink sink = new RecordingSink();
        AtomicLong clock = new AtomicLong();
        TransformStreamer streamer = new TransformStreamer(() -> config(20_000, 120), sink, clock::get, 100L);
        streamer.start();
        assertFalse(streamer.workerRunning());
        streamer.publish("rig:a", new FakeSource(List.of(player()), 60, 1));
        assertTrue(streamer.workerRunning());
        streamer.remove("rig:a");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (streamer.workerRunning() && System.nanoTime() < deadline) {
            clock.addAndGet(50L);
            Thread.sleep(10L);
        }
        assertFalse(streamer.workerRunning(), "an idle streamer must exit its worker");
        streamer.publish("rig:b", new FakeSource(List.of(player()), 60, 1));
        assertTrue(streamer.workerRunning());
        streamer.stop();
        assertFalse(streamer.workerRunning());
        assertEquals(0, streamer.sourceCount());
    }

    private static final class StubPacketEventsApi extends PacketEventsAPI<Object> {
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
            return null;
        }

        @Override
        public ChannelInjector getInjector() {
            return null;
        }
    }

    @Test
    void aFrameIsNeverSentInPartWhenTheBudgetRunsOut() {
        RecordingSink sink = new RecordingSink();
        TransformStreamer streamer = streamer(config(100, 120), sink, new AtomicLong());
        streamer.publish("rig:a", new FakeSource(List.of(player()), 100, 3));

        int sends = 0;
        for (long nowMs = 0L; nowMs < 1000L; nowMs += 10L) {
            sends += streamer.pass(nowMs);
        }

        assertEquals(0, sends % 3,
            "a rig with some parts on the new frame and some on the old is a torn frame");
        assertTrue(sends >= 99, "the budget must still admit the frames that fit");
    }
}
