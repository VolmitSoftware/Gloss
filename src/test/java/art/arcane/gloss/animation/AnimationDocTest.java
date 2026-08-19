package art.arcane.gloss.animation;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationDocTest {
    @Test
    void parseReadsTheV2Shape() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 2,
              "mode": "ascend",
              "frameIntervalMs": 500,
              "frames": ["&cA", "&6B"]
            }
            """;

        AnimationDoc doc = AnimationDoc.parse("rainbow.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(2L, doc.revision());
        assertEquals("ascend", doc.mode());
        assertEquals(AnimationMode.ASCEND, doc.toMode());
        assertEquals(500L, doc.frameIntervalMs());
        assertEquals(List.of("&cA", "&6B"), doc.frames());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        AnimationDoc original = new AnimationDoc(1, 9L, "ascend_descend", 250L, List.of("x", "y"));

        AnimationDoc decoded = AnimationDoc.parse("pingpong.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void modeNormalizesToLowercase() {
        AnimationDoc doc = new AnimationDoc(1, 1L, "RaNdOm", 100L, List.of("x"));

        assertEquals("random", doc.mode());
        assertEquals(AnimationMode.RANDOM, doc.toMode());
    }

    @Test
    void unknownModeIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new AnimationDoc(1, 1L, "sideways", 100L, List.of("x")));

        assertTrue(failure.getMessage().contains("sideways"));
        assertThrows(IllegalArgumentException.class, () -> new AnimationDoc(1, 1L, null, 100L, List.of("x")));
    }

    @Test
    void frameIntervalClampsToTheContractRange() {
        assertEquals(1L, new AnimationDoc(1, 1L, "ascend", 0L, List.of("x")).frameIntervalMs());
        assertEquals(1L, new AnimationDoc(1, 1L, "ascend", -50L, List.of("x")).frameIntervalMs());
        assertEquals(60_000L, new AnimationDoc(1, 1L, "ascend", 1_000_000L, List.of("x")).frameIntervalMs());
        assertEquals(500L, new AnimationDoc(1, 1L, "ascend", 500L, List.of("x")).frameIntervalMs());
    }

    @Test
    void emptyFramesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationDoc(1, 1L, "ascend", 100L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnimationDoc(1, 1L, "ascend", 100L, null));
    }

    @Test
    void nullFramesCollapseToEmptyStrings() {
        AnimationDoc doc = new AnimationDoc(1, 1L, "ascend", 100L, Arrays.asList("a", null, "b"));

        assertEquals(List.of("a", "", "b"), doc.frames());
    }

    @Test
    void legacyShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"target-framerate\":2.0,\"animation-type\":\"ASCEND\",\"frames\":[\"&cGloss\"]}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> AnimationDoc.parse("rainbow.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationDoc(1, 0L, "ascend", 100L, List.of("x")));
        assertThrows(IllegalArgumentException.class,
            () -> new AnimationDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, "ascend", 100L, List.of("x")));
    }
}
