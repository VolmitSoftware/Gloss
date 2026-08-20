package art.arcane.gloss.hologram;

import art.arcane.gloss.animation.AnimationClip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class AnimationTemplate implements TextFrameSource {
    public sealed interface Segment permits LiteralSegment, SlotSegment {
    }

    public record LiteralSegment(String text) implements Segment {
    }

    public record SlotSegment(AnimationClip clip, List<String> renderedFrames) implements Segment {
    }

    private final List<Segment> segments;
    private final boolean slotted;
    private final int[] cachedIndices;
    private boolean cacheValid;
    private String cachedText;

    private AnimationTemplate(List<Segment> segments, boolean slotted) {
        this.segments = List.copyOf(segments);
        this.slotted = slotted;
        this.cachedIndices = new int[countSlots(segments)];
    }

    public static AnimationTemplate compile(String raw, Predicate<String> isFunction,
                                            Function<String, AnimationClip> fastClipResolver,
                                            UnaryOperator<String> renderer) {
        return compile(raw, isFunction, fastClipResolver, renderer, clip -> null);
    }

    public static AnimationTemplate compile(String raw, Predicate<String> isFunction,
                                            Function<String, AnimationClip> fastClipResolver,
                                            UnaryOperator<String> renderer,
                                            Function<AnimationClip, List<String>> sharedFrames) {
        List<Segment> segments = new ArrayList<>();
        Map<String, List<String>> frameCache = new HashMap<>();
        boolean slotted = false;
        int cursor = 0;
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                break;
            }

            String name = raw.substring(open + 1, close);
            AnimationClip clip = fastClipResolver.apply(name);
            if (clip != null) {
                String literal = raw.substring(cursor, open);
                if (!literal.isEmpty()) {
                    segments.add(new LiteralSegment(renderer.apply(literal)));
                }

                List<String> shared = sharedFrames.apply(clip);
                segments.add(new SlotSegment(clip, shared != null ? shared
                    : frameCache.computeIfAbsent(name, key -> renderFrames(clip, renderer))));
                slotted = true;
                cursor = close + 1;
                open = raw.indexOf('|', cursor);
                continue;
            }
            if (isFunction.test(name)) {
                open = raw.indexOf('|', close + 1);
                continue;
            }

            open = close;
        }

        String tail = raw.substring(cursor);
        if (!tail.isEmpty() || segments.isEmpty()) {
            segments.add(new LiteralSegment(renderer.apply(tail)));
        }

        return new AnimationTemplate(segments, slotted);
    }

    public boolean hasSlots() {
        return slotted;
    }

    public List<Segment> segments() {
        return segments;
    }

    @Override
    public String compose(long nowMs) {
        if (segments.size() == 1 && segments.getFirst() instanceof LiteralSegment only) {
            return only.text();
        }

        boolean unchanged = cacheValid;
        int slot = 0;
        for (Segment segment : segments) {
            if (!(segment instanceof SlotSegment slotSegment)) {
                continue;
            }

            int index = slotSegment.clip().frameIndexAt(nowMs);
            if (cachedIndices[slot] != index) {
                unchanged = false;
                cachedIndices[slot] = index;
            }

            slot++;
        }
        if (unchanged) {
            return cachedText;
        }

        StringBuilder out = new StringBuilder(64);
        slot = 0;
        for (Segment segment : segments) {
            if (segment instanceof LiteralSegment literal) {
                out.append(literal.text());
                continue;
            }
            if (segment instanceof SlotSegment slotSegment) {
                if (!slotSegment.renderedFrames().isEmpty()) {
                    out.append(slotSegment.renderedFrames().get(cachedIndices[slot]));
                }

                slot++;
            }
        }

        cacheValid = true;
        cachedText = out.toString();
        return cachedText;
    }

    private static int countSlots(List<Segment> segments) {
        int slots = 0;
        for (Segment segment : segments) {
            if (segment instanceof SlotSegment) {
                slots++;
            }
        }

        return slots;
    }

    private static List<String> renderFrames(AnimationClip clip, UnaryOperator<String> renderer) {
        List<String> rendered = new ArrayList<>(clip.frames().size());
        for (String frame : clip.frames()) {
            rendered.add(renderer.apply(frame));
        }

        return List.copyOf(rendered);
    }
}
