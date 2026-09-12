package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.gloss.motion.MotionService;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramMotionTest {
    private static final String MOVING = """
        {
          "schemaVersion": 3, "revision": 1,
          "anchor": {"world": "world", "position": [0, 64, 0]},
          "lines": ["&dFloating"],
          "motion": "breathe"
        }
        """;

    private static final String STILL = """
        {
          "schemaVersion": 3, "revision": 2,
          "anchor": {"world": "world", "position": [0, 64, 0]},
          "lines": ["&dFloating"]
        }
        """;

    @TempDir
    File directory;

    @BeforeAll
    static void installPacketEventsApi() {
        PacketEvents.setAPI(new StubPacketEventsApi());
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    @Test
    void aHologramWithMotionStreamsTransformsAndSendsNoTextFrames() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            MotionService motion = harness.motionService();
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("floating", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(HologramDoc.parse("floating.json", MOVING));
            hologram.update();
            harness.drainDelayed();

            assertTrue(motion.ids().contains("breathe"), "the shipped breathe clip must load");
            assertTrue(hologram.motionPlaying());
            assertEquals(List.of(viewer.proxy), hologram.motionViewers());

            int textFramesBefore = harness.sender.sent.size();
            List<PacketWrapper<?>> frame = hologram.motionFrame(hologram.motionStartedAtMs() + 500L);

            assertFalse(frame.isEmpty(), "a playing hologram clip must compose transform packets");
            assertInstanceOf(WrapperPlayServerEntityMetadata.class, frame.getFirst());
            assertEquals(textFramesBefore, harness.sender.sent.size(),
                "transform frames must never enter the text animator");
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    @Test
    void clearingTheMotionStopsTheStream() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            harness.motionService();
            WorldState world = harness.world("world");
            harness.join("Viewer", world, 0.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("floating", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(HologramDoc.parse("floating.json", MOVING));
            hologram.update();
            harness.drainDelayed();
            assertTrue(hologram.motionPlaying());

            hologram.apply(HologramDoc.parse("floating.json", STILL));
            hologram.update();
            harness.drainDelayed();

            assertFalse(hologram.motionPlaying());
            assertTrue(hologram.motionFrame(System.currentTimeMillis()).isEmpty());
        }
    }

    @Test
    void anUnknownMotionLeavesTheHologramStill() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            harness.motionService();
            WorldState world = harness.world("world");
            harness.join("Viewer", world, 0.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("floating", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(HologramDoc.parse("floating.json", """
                {
                  "schemaVersion": 3, "revision": 1,
                  "anchor": {"world": "world", "position": [0, 64, 0]},
                  "lines": ["&dFloating"],
                  "motion": "no-such-clip"
                }
                """));
            hologram.update();
            harness.drainDelayed();

            assertFalse(hologram.motionPlaying());
            assertTrue(harness.schedulerErrors.isEmpty());
        }
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
}
