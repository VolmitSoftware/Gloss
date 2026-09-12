package art.arcane.gloss.motion;

import art.arcane.gloss.animation.clip.ClipPlan;
import art.arcane.gloss.animation.clip.ClipSample;
import art.arcane.gloss.animation.clip.LoopMode;
import art.arcane.gloss.animation.clip.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionDocTest {
    private static final String SPEC_EXAMPLE = """
        {
          "schemaVersion": 1, "revision": 1,
          "durationTicks": 40, "loop": "pingpong", "fps": 30,
          "tracks": [
            { "bone": "root", "channel": "translation.y", "blend": "add", "keyframes": [ { "tick": 0, "value": 0, "easing": "ease_in_out" }, { "tick": 20, "value": 0.15 }, { "tick": 40, "value": 0 } ] },
            { "bone": "head", "channel": "rotation.y", "blend": "add", "keyframes": [ { "tick": 0, "value": -10 }, { "tick": 40, "value": 10, "easing": "bezier(0.42,0,0.58,1)" } ] },
            { "bone": "*", "channel": "glow", "blend": "replace", "keyframes": [ { "tick": 0, "value": "#00000000" }, { "tick": 20, "value": "#FFAA55" } ] },
            { "bone": "lid", "channel": "visible", "keyframes": [ { "tick": 0, "value": true }, { "tick": 30, "value": false, "easing": "hold" } ] }
          ]
        }
        """;

    @Test
    void specExampleParsesWithEveryChannelKind() {
        MotionDoc doc = MotionDoc.parse("breathe.json", SPEC_EXAMPLE);

        assertEquals(40.0D, doc.durationTicks(), 1.0E-9D);
        assertEquals(LoopMode.PINGPONG, doc.loopMode());
        assertEquals(30, doc.fps());
        assertEquals(4, doc.tracks().size());
        assertEquals(MotionChannel.TRANSLATION_Y, doc.tracks().get(0).channelKind());
        assertEquals(Target.TRANSLATION_Y, MotionChannel.TRANSLATION_Y.target());
        assertEquals("*", doc.tracks().get(2).bone());
        assertEquals(0xFFFFAA55L, Math.round(doc.tracks().get(2).keyframes().get(1).numericValue()) & 0xFFFFFFFFL);
        assertEquals(1.0D, doc.tracks().get(3).keyframes().get(0).numericValue(), 1.0E-9D);
        assertEquals(0.0D, doc.tracks().get(3).keyframes().get(1).numericValue(), 1.0E-9D);
    }

    @Test
    void compiledPlanSamplesPerBoneWithStarTracksReachingEveryBone() {
        MotionDoc doc = MotionDoc.parse("breathe.json", SPEC_EXAMPLE);
        ClipPlan plan = ClipPlan.compile(MotionService.compile(doc));

        Map<String, ClipSample> bones = plan.sampleBones(List.of("root", "head", "lid", "other"),
            List.of(new ClipPlan.ActiveClip(MotionService.PLAY, 20.0D)));
        assertEquals(0.15D, bones.get("root").offsetY(), 1.0E-6D);
        assertEquals(0.0D, bones.get("head").rotationY(), 1.0E-6D);
        assertEquals(0xFFFFAA55L, bones.get("root").glowArgb());
        assertEquals(0xFFFFAA55L, bones.get("other").glowArgb());
        assertEquals(0.0D, bones.get("other").offsetY(), 1.0E-9D);
        assertTrue(bones.get("lid").visible());
        assertTrue(!plan.sampleBones(List.of("lid"), List.of(new ClipPlan.ActiveClip(MotionService.PLAY, 35.0D)))
            .get("lid").visible());
    }

    @Test
    void unknownChannelIsRefused() {
        String raw = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10,
              "tracks": [ { "bone": "root", "channel": "wobble.x", "keyframes": [ { "tick": 0, "value": 1 } ] } ] }
            """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> MotionDoc.parse("bad.json", raw));
        assertTrue(failure.getMessage().contains("wobble.x"), failure.getMessage());
    }

    @Test
    void valueTypesAreCheckedPerChannel() {
        String stringOnNumeric = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10,
              "tracks": [ { "bone": "root", "channel": "scale.x", "keyframes": [ { "tick": 0, "value": "#FF0000" } ] } ] }
            """;
        assertThrows(IllegalArgumentException.class, () -> MotionDoc.parse("bad.json", stringOnNumeric));
        String numberOnVisible = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10,
              "tracks": [ { "bone": "root", "channel": "visible", "keyframes": [ { "tick": 0, "value": 1 } ] } ] }
            """;
        assertThrows(IllegalArgumentException.class, () -> MotionDoc.parse("bad.json", numberOnVisible));
        String badColor = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10,
              "tracks": [ { "bone": "root", "channel": "glow", "keyframes": [ { "tick": 0, "value": "orange" } ] } ] }
            """;
        assertThrows(IllegalArgumentException.class, () -> MotionDoc.parse("bad.json", badColor));
    }

    @Test
    void keyframesOutsideTheDurationAndBadLoopsAreRefusedAtLoad() {
        String outside = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10,
              "tracks": [ { "bone": "root", "channel": "scale.x", "keyframes": [ { "tick": 12, "value": 1 } ] } ] }
            """;
        assertThrows(IllegalArgumentException.class, () -> MotionDoc.parse("bad.json", outside));
        String badLoop = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10, "loop": "bounce",
              "tracks": [ { "bone": "root", "channel": "scale.x", "keyframes": [ { "tick": 0, "value": 1 } ] } ] }
            """;
        assertThrows(IllegalArgumentException.class, () -> MotionDoc.parse("bad.json", badLoop));
    }

    @Test
    void defaultsFillLoopFpsBlendAndBone() {
        String raw = """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 10,
              "tracks": [ { "channel": "rotation.y", "keyframes": [ { "tick": 0, "value": 0 }, { "tick": 10, "value": 90 } ] } ] }
            """;
        MotionDoc doc = MotionDoc.parse("spin.json", raw);
        assertEquals(LoopMode.LOOP, doc.loopMode());
        assertEquals(MotionDoc.DEFAULT_FPS, doc.fps());
        assertEquals("root", doc.tracks().get(0).bone());
        assertEquals("add", doc.tracks().get(0).blend());
    }
}
