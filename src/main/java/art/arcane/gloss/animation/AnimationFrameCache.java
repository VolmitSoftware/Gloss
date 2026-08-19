package art.arcane.gloss.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class AnimationFrameCache {
    private record Entry(AnimationClip clip, long generation, List<String> frames) {
    }

    private final UnaryOperator<String> renderer;
    private final Map<String, Entry> entries;

    public AnimationFrameCache(UnaryOperator<String> renderer) {
        this.renderer = renderer;
        this.entries = new ConcurrentHashMap<>();
    }

    public void clear() {
        entries.clear();
    }

    public List<String> staticFrames(AnimationClip clip, long generation) {
        if (clip == null) {
            return null;
        }

        Entry cached = entries.get(clip.id());
        if (cached != null && cached.clip() == clip && cached.generation() == generation) {
            return cached.frames();
        }

        List<String> rendered = viewerIndependent(clip) ? render(clip) : null;
        entries.put(clip.id(), new Entry(clip, generation, rendered));
        return rendered;
    }

    private List<String> render(AnimationClip clip) {
        List<String> frames = clip.frames();
        List<String> rendered = new ArrayList<>(frames.size());
        for (String frame : frames) {
            rendered.add(renderer.apply(frame));
        }

        return List.copyOf(rendered);
    }

    private static boolean viewerIndependent(AnimationClip clip) {
        for (String frame : clip.frames()) {
            if (viewerDependent(frame)) {
                return false;
            }
        }

        return true;
    }

    private static boolean viewerDependent(String frame) {
        if (frame.indexOf('%') >= 0) {
            return true;
        }

        int open = frame.indexOf('|');
        return open >= 0 && frame.indexOf('|', open + 1) >= 0;
    }
}
