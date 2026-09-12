package art.arcane.gloss.rig;

import art.arcane.gloss.util.common.StubPacketEventsApi;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rig whose pose has not moved must not keep sending the same transform for every part: those
 * packets are charged against the shared transform budget and round-robin every genuinely moving
 * rig down to make room for a frozen one.
 */
class RigFramesDiffTest {
    @BeforeAll
    static void installPacketEventsApi() {
        StubPacketEventsApi.install();
    }

    @AfterAll
    static void clearPacketEventsApi() {
        StubPacketEventsApi.clear();
    }

    private static final List<Part> PARTS = List.of(part("head"), part("body"));
    private static final int[] IDS = {10, 11};

    @Test
    void aPoseThatHasNotMovedCostsNoPackets() {
        Map<String, Transform> pose = Map.of("head", Transform.of(0.0F, 1.0F, 0.0F),
            "body", Transform.of(0.0F, 0.0F, 0.0F));
        Map<String, Transform> sent = new HashMap<>();

        assertEquals(2, RigFrames.transformPackets(PARTS, IDS, pose, 2, sent).size());
        assertEquals(List.of(), RigFrames.transformPackets(PARTS, IDS, pose, 2, sent));
    }

    @Test
    void onlyThePartThatMovedIsSentAgain() {
        Map<String, Transform> first = Map.of("head", Transform.of(0.0F, 1.0F, 0.0F),
            "body", Transform.of(0.0F, 0.0F, 0.0F));
        Map<String, Transform> sent = new HashMap<>();
        RigFrames.transformPackets(PARTS, IDS, first, 2, sent);

        Map<String, Transform> moved = Map.of("head", Transform.of(0.0F, 2.0F, 0.0F),
            "body", Transform.of(0.0F, 0.0F, 0.0F));
        List<PacketWrapper<?>> packets = RigFrames.transformPackets(PARTS, IDS, moved, 2, sent);

        assertEquals(1, packets.size());
    }

    @Test
    void withoutASentMapEveryPartIsStillEmitted() {
        Map<String, Transform> pose = Map.of("head", Transform.of(0.0F, 1.0F, 0.0F),
            "body", Transform.of(0.0F, 0.0F, 0.0F));

        assertEquals(2, RigFrames.transformPackets(PARTS, IDS, pose, 2).size());
    }

    @Test
    void transformsCompareByValueSoTheDiffCanWork() {
        assertTrue(new Vector3f(1.0F, 2.0F, 3.0F).equals(new Vector3f(1.0F, 2.0F, 3.0F)));
        assertEquals(Transform.of(1.0F, 2.0F, 3.0F), Transform.of(1.0F, 2.0F, 3.0F));
    }

    private static Part part(String id) {
        return new Part(id, id, PartType.BLOCK, "stone", null, null, Transform.identity(), null, null, null);
    }
}
