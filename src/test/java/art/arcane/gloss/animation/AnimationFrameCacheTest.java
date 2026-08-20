package art.arcane.gloss.animation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AnimationFrameCacheTest {
    private static AnimationClip clip(String id, List<String> frames) {
        return new AnimationClip(id, 100.0D, AnimationMode.ASCEND, frames);
    }

    private static AnimationFrameCache cache(AtomicInteger renders) {
        UnaryOperator<String> renderer = raw -> {
            renders.incrementAndGet();
            return "<" + raw + ">";
        };
        return new AnimationFrameCache(renderer);
    }

    @Test
    void viewerIndependentFramesRenderOnceAndAreShared() {
        AtomicInteger renders = new AtomicInteger();
        AnimationFrameCache frameCache = cache(renders);
        AnimationClip spinner = clip("spinner", List.of("&a|", "&a/", "&a-"));

        List<String> first = frameCache.staticFrames(spinner, 0L);
        List<String> second = frameCache.staticFrames(spinner, 0L);

        assertEquals(List.of("<&a|>", "<&a/>", "<&a->"), first);
        assertSame(first, second, "repeat compiles must reuse the rendered frame list");
        assertEquals(3, renders.get(), "frames must render exactly once per clip revision");
    }

    @Test
    void viewerDependentFramesAreNeverCached() {
        AtomicInteger renders = new AtomicInteger();
        AnimationFrameCache frameCache = cache(renders);

        assertNull(frameCache.staticFrames(clip("papi", List.of("a", "%player_name%")), 0L),
            "placeholder frames must stay on the per-viewer render path");
        assertNull(frameCache.staticFrames(clip("fn", List.of("|rainbow|")), 0L),
            "function frames must stay on the per-viewer render path");
        assertNull(frameCache.staticFrames(clip("expression", List.of("{{ player.name }}")), 0L),
            "inline player expressions must stay on the per-viewer render path");
        assertEquals(0, renders.get(), "viewer-dependent clips must never be pre-rendered");
    }

    @Test
    void emojiGenerationBumpInvalidatesTheRenderedFrames() {
        AtomicInteger renders = new AtomicInteger();
        AnimationFrameCache frameCache = cache(renders);
        AnimationClip spinner = clip("spinner", List.of("a", "b"));

        frameCache.staticFrames(spinner, 0L);
        frameCache.staticFrames(spinner, 1L);

        assertEquals(4, renders.get(), "a new emoji generation must re-render the frames");
    }

    @Test
    void reloadedClipInstanceInvalidatesTheRenderedFrames() {
        AtomicInteger renders = new AtomicInteger();
        AnimationFrameCache frameCache = cache(renders);

        frameCache.staticFrames(clip("spinner", List.of("a", "b")), 0L);
        List<String> reloaded = frameCache.staticFrames(clip("spinner", List.of("c", "d")), 0L);

        assertEquals(List.of("<c>", "<d>"), reloaded, "a hotloaded clip must serve its new frames");
        assertEquals(4, renders.get());
    }

    @Test
    void clearDropsEveryEntry() {
        AtomicInteger renders = new AtomicInteger();
        AnimationFrameCache frameCache = cache(renders);
        AnimationClip spinner = clip("spinner", List.of("a"));

        frameCache.staticFrames(spinner, 0L);
        frameCache.clear();
        frameCache.staticFrames(spinner, 0L);

        assertEquals(2, renders.get());
    }

    @Test
    void nullClipYieldsNoFrames() {
        assertNull(cache(new AtomicInteger()).staticFrames(null, 0L));
    }
}
