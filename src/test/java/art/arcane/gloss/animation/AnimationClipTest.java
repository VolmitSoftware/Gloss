package art.arcane.gloss.animation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationClipTest {
    private static final List<String> FRAMES = List.of("A", "B", "C", "D");

    @Test
    void ascendCyclesForward() {
        AnimationClip clip = new AnimationClip("a", 2.0D, AnimationMode.ASCEND, FRAMES);
        assertEquals("A", clip.frameAt(0L));
        assertEquals("A", clip.frameAt(499L));
        assertEquals("B", clip.frameAt(500L));
        assertEquals("C", clip.frameAt(1000L));
        assertEquals("D", clip.frameAt(1500L));
        assertEquals("A", clip.frameAt(2000L));
    }

    @Test
    void descendCyclesBackward() {
        AnimationClip clip = new AnimationClip("d", 2.0D, AnimationMode.DESCEND, FRAMES);
        assertEquals("D", clip.frameAt(0L));
        assertEquals("C", clip.frameAt(500L));
        assertEquals("B", clip.frameAt(1000L));
        assertEquals("A", clip.frameAt(1500L));
        assertEquals("D", clip.frameAt(2000L));
    }

    @Test
    void ascendDescendPingPongs() {
        AnimationClip clip = new AnimationClip("p", 2.0D, AnimationMode.ASCEND_DESCEND, FRAMES);
        assertEquals("A", clip.frameAt(0L));
        assertEquals("B", clip.frameAt(500L));
        assertEquals("C", clip.frameAt(1000L));
        assertEquals("D", clip.frameAt(1500L));
        assertEquals("D", clip.frameAt(2000L));
        assertEquals("C", clip.frameAt(2500L));
        assertEquals("B", clip.frameAt(3000L));
        assertEquals("A", clip.frameAt(3500L));
        assertEquals("A", clip.frameAt(4000L));
    }

    @Test
    void randomIsDeterministicPerTimestamp() {
        AnimationClip clip = new AnimationClip("r", 2.0D, AnimationMode.RANDOM, FRAMES);
        assertEquals(clip.frameAt(1234L), clip.frameAt(1234L));
        assertEquals(clip.frameAt(987654L), clip.frameAt(987654L));
    }

    @Test
    void randomIsStableWithinFramePeriod() {
        AnimationClip clip = new AnimationClip("r", 2.0D, AnimationMode.RANDOM, FRAMES);
        assertEquals(clip.frameAt(0L), clip.frameAt(499L));
        assertEquals(clip.frameAt(1500L), clip.frameAt(1999L));
    }

    @Test
    void randomVariesAcrossPeriods() {
        AnimationClip clip = new AnimationClip("r", 2.0D, AnimationMode.RANDOM, FRAMES);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            seen.add(clip.frameAt(i * 500L));
        }
        assertTrue(seen.size() > 1);
    }

    @Test
    void singleFrameAlwaysReturnsThatFrame() {
        AnimationClip clip = new AnimationClip("s", 2.0D, AnimationMode.RANDOM, List.of("only"));
        assertEquals("only", clip.frameAt(0L));
        assertEquals("only", clip.frameAt(123456789L));
    }

    @Test
    void emptyFramesRenderEmpty() {
        AnimationClip clip = new AnimationClip("e", 2.0D, AnimationMode.ASCEND, List.of());
        assertEquals("", clip.frameAt(0L));
    }

    @Test
    void nonPositiveFramerateClampsToOneFramePerSecond() {
        AnimationClip clip = new AnimationClip("z", 0.0D, AnimationMode.ASCEND, List.of("A", "B"));
        assertEquals("A", clip.frameAt(0L));
        assertEquals("B", clip.frameAt(1000L));
        assertEquals("B", clip.frameAt(1999L));
        assertEquals("A", clip.frameAt(2000L));
    }

    @Test
    void largeTimestampsStayInRange() {
        AnimationClip clip = new AnimationClip("l", 60.0D, AnimationMode.ASCEND, FRAMES);
        assertTrue(FRAMES.contains(clip.frameAt(System.currentTimeMillis())));
        assertTrue(FRAMES.contains(clip.frameAt(Long.MAX_VALUE / 2L)));
    }

    @Test
    void frameIndexMatchesFrameAtForEveryMode() {
        for (AnimationMode mode : AnimationMode.values()) {
            AnimationClip clip = new AnimationClip("i", 8.0D, mode, FRAMES);
            for (long now = 0L; now < 2000L; now += 63L) {
                assertEquals(clip.frameAt(now), FRAMES.get(clip.frameIndexAt(now)), mode + " @ " + now);
            }
        }
    }

    @Test
    void frameIndexOfDegenerateClipsIsZero() {
        assertEquals(0, new AnimationClip("s", 2.0D, AnimationMode.RANDOM, List.of("only")).frameIndexAt(987654L));
        assertEquals(0, new AnimationClip("e", 2.0D, AnimationMode.ASCEND, List.of()).frameIndexAt(987654L));
    }
}
