package art.arcane.gloss.rig;

import art.arcane.gloss.animation.clip.ClipPlan;
import art.arcane.gloss.animation.clip.ClipSample;
import art.arcane.gloss.motion.MotionDoc;
import art.arcane.gloss.motion.MotionService;
import art.arcane.gloss.util.common.DisplayEntity;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RigInstanceFrameTest {
    private static final float EPSILON = 1.0E-4F;

    @BeforeAll
    static void installPacketEventsApi() {
        PacketEvents.setAPI(new StubPacketEventsApi());
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    private static MotionDoc breathe() throws IOException {
        try (InputStream stream = RigInstanceFrameTest.class.getResourceAsStream("/defaults/motion/breathe.json")) {
            assertNotNull(stream);
            return MotionDoc.parse("breathe.json", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static EntityData<?> metadata(WrapperPlayServerEntityMetadata packet, DisplayEntity.MetadataIndex index) {
        for (EntityData<?> data : packet.getEntityMetadata()) {
            if (data.getIndex() == index.index()) {
                return data;
            }
        }
        throw new AssertionError("no metadata at index " + index);
    }

    @Test
    void twoBoneRigAtTickTenOfBreatheLiftsTheChildPartByTheEasedTranslation() throws IOException {
        RigDoc doc = RigDoc.parse("pedestal.json", RigDocTest.PEDESTAL);
        RigModel model = doc.model();
        List<Part> parts = doc.toParts();
        ClipPlan plan = ClipPlan.compile(MotionService.compile(breathe()));
        Map<String, ClipSample> samples = plan.sampleBones(model.bones().keySet(),
            List.of(new ClipPlan.ActiveClip(MotionService.PLAY, 10.0D)));

        Map<String, Transform> pose = RigPose.partTransforms(model, parts, Transform.identity(), samples);
        Transform lid = pose.get("lidPart");
        assertEquals(0.0F, lid.translation().getX(), EPSILON);
        assertEquals(0.009375F + 0.9F * 1.0025F, lid.translation().getY(), EPSILON);
        assertEquals(0.0F, lid.translation().getZ(), EPSILON);
        assertEquals(1.0F, lid.scale().getX(), EPSILON);
        assertEquals(0.1F * 1.0025F, lid.scale().getY(), EPSILON);
        Transform base = pose.get("base");
        assertEquals(-0.5F, base.translation().getX(), EPSILON);
        assertEquals(0.009375F, base.translation().getY(), EPSILON);
        assertEquals(-0.5F, base.translation().getZ(), EPSILON);
        assertEquals(0.9F * 1.0025F, base.scale().getY(), EPSILON);

        int[] ids = {100, 101, 102};
        List<PacketWrapper<?>> packets = RigFrames.transformPackets(parts, ids, pose, 2);
        assertEquals(3, packets.size());
        WrapperPlayServerEntityMetadata lidPacket = (WrapperPlayServerEntityMetadata) packets.get(1);
        assertEquals(101, lidPacket.getEntityId());
        Vector3f translation = (Vector3f) metadata(lidPacket, DisplayEntity.MetadataIndex.TRANSLATION).getValue();
        assertEquals(0.009375F + 0.9F * 1.0025F, translation.getY(), EPSILON);
        Vector3f scale = (Vector3f) metadata(lidPacket, DisplayEntity.MetadataIndex.SCALE).getValue();
        assertEquals(0.1F * 1.0025F, scale.getY(), EPSILON);
        assertEquals(2, metadata(lidPacket, DisplayEntity.MetadataIndex.INTERPOLATION_DURATION).getValue());
        assertEquals(0, metadata(lidPacket, DisplayEntity.MetadataIndex.INTERPOLATION_DELAY).getValue());
    }

    @Test
    void instanceYawTurnsTheWholeRigAndScaleGrowsIt() {
        RigDoc doc = RigDoc.parse("pedestal.json", RigDocTest.PEDESTAL);
        Transform root = RigPose.rootTransform(90.0F, 0.0F, 2.0F);
        Map<String, Transform> pose = RigPose.partTransforms(doc.model(), doc.toParts(), root, Map.of());
        Transform lid = pose.get("lidPart");
        assertEquals(1.8F, lid.translation().getY(), EPSILON);
        assertEquals(0.0F, lid.translation().getX(), 1.0E-3F);
        assertEquals(0.0F, lid.translation().getZ(), 1.0E-3F);
        Transform base = pose.get("base");
        assertEquals(-1.0F, base.translation().getZ(), 1.0E-3F);
        assertEquals(1.0F, base.translation().getX(), 1.0E-3F);
        assertEquals(2.0F, base.scale().getX(), EPSILON);
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
