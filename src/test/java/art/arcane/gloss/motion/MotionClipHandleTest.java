package art.arcane.gloss.motion;

import art.arcane.gloss.animation.clip.ClipPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionClipHandleTest {
    private static MotionDoc doc(String loop) {
        return MotionDoc.parse(loop + ".json", """
            { "schemaVersion": 1, "revision": 1, "durationTicks": 40, "loop": "%s", "fps": 30,
              "tracks": [ { "bone": "root", "channel": "translation.y", "keyframes": [ { "tick": 0, "value": 0 }, { "tick": 40, "value": 1 } ] } ] }
            """.formatted(loop));
    }

    private static MotionClipHandle handle(MotionDoc doc) {
        MotionService.Compiled compiled = new MotionService.Compiled(doc, ClipPlan.compile(MotionService.compile(doc)));
        return new MotionClipHandle(id -> id.equals("clip") ? Optional.of(compiled) : Optional.empty());
    }

    @Test
    void pingpongReflectsElapsedTicksAcrossTheDuration() {
        MotionClipHandle handle = handle(doc("pingpong"));
        assertTrue(handle.play("clip", 0L));

        assertEquals(20.0D, handle.elapsedTicks(20L * 50L), 1.0E-9D);
        assertEquals(40.0D, handle.elapsedTicks(40L * 50L), 1.0E-9D);
        assertEquals(20.0D, handle.elapsedTicks(60L * 50L), 1.0E-9D);
        assertEquals(0.0D, handle.elapsedTicks(80L * 50L), 1.0E-9D);
        assertEquals(10.0D, handle.elapsedTicks(90L * 50L), 1.0E-9D);
        assertFalse(handle.finished(1000L * 50L));
        assertEquals(0.5D, handle.sampleBones(List.of("root"), 20L * 50L).get("root").offsetY(), 1.0E-6D);
    }

    @Test
    void onceStopsAtTheDurationAndReportsFinished() {
        MotionClipHandle handle = handle(doc("once"));
        assertTrue(handle.play("clip", 100L));

        assertFalse(handle.finished(100L + 39L * 50L));
        assertEquals(40.0D, handle.elapsedTicks(100L + 100L * 50L), 1.0E-9D);
        assertTrue(handle.finished(100L + 40L * 50L));
        assertEquals(1.0D, handle.sampleBones(List.of("root"), 100L + 100L * 50L).get("root").offsetY(), 1.0E-6D);
    }

    @Test
    void loopWrapsAndNeverFinishes() {
        MotionClipHandle handle = handle(doc("loop"));
        assertTrue(handle.play("clip", 0L));

        assertEquals(10.0D, handle.elapsedTicks(50L * 50L), 1.0E-9D);
        assertFalse(handle.finished(50L * 50L));
        assertEquals(0.25D, handle.sampleBones(List.of("root"), 50L * 50L).get("root").offsetY(), 1.0E-6D);
    }

    @Test
    void unknownMotionDoesNotPlayAndStopClearsTheClip() {
        MotionClipHandle handle = handle(doc("loop"));
        assertFalse(handle.play("missing", 0L));
        assertFalse(handle.playing());
        assertTrue(handle.play("clip", 0L));
        assertTrue(handle.playing());
        assertEquals(30, handle.fps());
        assertEquals("clip", handle.motionId());
        handle.stop();
        assertFalse(handle.playing());
        assertTrue(handle.sampleBones(List.of("root"), 500L).get("root").isNeutral());
    }

    @Test
    void aFinishedOnceClipStopsStreamingButKeepsItsFinalPose() {
        MotionClipHandle handle = handle(doc("once"));
        assertTrue(handle.play("clip", 0L));

        assertTrue(handle.streaming(0L));
        assertTrue(handle.streaming(39L * 50L));
        assertFalse(handle.streaming(40L * 50L),
            "a frozen rig must stop spending the shared transform budget once its clip has run out");
        assertTrue(handle.playing(), "the pose still holds the clip's last frame");
        assertEquals(40.0D, handle.elapsedTicks(400L * 50L), 1.0E-9D);
    }

    @Test
    void aLoopingClipNeverStopsStreaming() {
        MotionClipHandle handle = handle(doc("loop"));
        assertTrue(handle.play("clip", 0L));

        assertTrue(handle.streaming(10_000L * 50L));
    }

    @Test
    void aHandleWithNothingPlayingIsNotStreaming() {
        assertFalse(handle(doc("loop")).streaming(0L));
    }
}
