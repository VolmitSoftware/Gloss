package art.arcane.gloss.hologram;

import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationTemplateTest {
    private static final AnimationClip FAST = new AnimationClip("fast", 100.0D, AnimationMode.ASCEND, List.of("A", "B", "C", "D"));
    private static final UnaryOperator<String> MARK_RENDERER = raw -> "<" + raw + ">";
    private static final UnaryOperator<String> IDENTITY_RENDERER = raw -> raw;

    private static AnimationTemplate compile(String raw, Set<String> functions, Map<String, AnimationClip> fastClips,
                                             UnaryOperator<String> renderer) {
        Predicate<String> isFunction = name -> functions.contains(name) || fastClips.containsKey(name);
        Function<String, AnimationClip> resolver = fastClips::get;
        return AnimationTemplate.compile(raw, isFunction, resolver, renderer);
    }

    @Test
    void plainTextCompilesToSingleRenderedLiteral() {
        AnimationTemplate template = compile("hello world", Set.of(), Map.of(), MARK_RENDERER);

        assertFalse(template.hasSlots());
        assertEquals(1, template.segments().size());
        assertEquals("<hello world>", template.compose(0L));
    }

    @Test
    void emptyInputStillComposes() {
        AnimationTemplate template = compile("", Set.of(), Map.of(), MARK_RENDERER);

        assertFalse(template.hasSlots());
        assertEquals("<>", template.compose(0L));
    }

    @Test
    void fastClipTokenBecomesSlot() {
        AnimationTemplate template = compile("x |animation.fast| y", Set.of(), Map.of("animation.fast", FAST), MARK_RENDERER);

        assertTrue(template.hasSlots());
        assertEquals(3, template.segments().size());
        assertEquals("<x ><A>< y>", template.compose(0L));
        assertEquals("<x ><B>< y>", template.compose(10L));
        assertEquals("<x ><A>< y>", template.compose(40L));
    }

    @Test
    void slotFramesAreRendered() {
        AnimationTemplate template = compile("|animation.fast|", Set.of(), Map.of("animation.fast", FAST), MARK_RENDERER);

        assertEquals("<A>", template.compose(0L));
        assertEquals("<D>", template.compose(30L));
    }

    @Test
    void registeredSlowFunctionStaysInLiteralForTheRenderer() {
        AnimationTemplate template = compile("a |rainbow| b |animation.fast| c",
            Set.of("rainbow"), Map.of("animation.fast", FAST), IDENTITY_RENDERER);

        assertTrue(template.hasSlots());
        assertEquals("a |rainbow| b A c", template.compose(0L));
    }

    @Test
    void unknownPipeNameFollowsPipelineScanSemantics() {
        AnimationTemplate template = compile("|notfunc|animation.fast| b",
            Set.of(), Map.of("animation.fast", FAST), IDENTITY_RENDERER);

        assertTrue(template.hasSlots());
        assertEquals("|notfuncA b", template.compose(0L));
    }

    @Test
    void functionConsumingBothPipesHidesFollowingAnimationName() {
        AnimationTemplate template = compile("a |foo|animation.fast| b",
            Set.of("foo"), Map.of("animation.fast", FAST), IDENTITY_RENDERER);

        assertFalse(template.hasSlots());
        assertEquals("a |foo|animation.fast| b", template.compose(0L));
    }

    @Test
    void unclosedPipeStaysLiteral() {
        AnimationTemplate template = compile("a |animation.fast", Set.of(), Map.of("animation.fast", FAST), IDENTITY_RENDERER);

        assertFalse(template.hasSlots());
        assertEquals("a |animation.fast", template.compose(0L));
    }

    @Test
    void repeatedClipSharesRenderedFrames() {
        AtomicInteger renders = new AtomicInteger();
        UnaryOperator<String> counting = raw -> {
            renders.incrementAndGet();
            return raw;
        };
        AnimationTemplate template = compile("|animation.fast|-|animation.fast|",
            Set.of(), Map.of("animation.fast", FAST), counting);

        assertEquals("A-A", template.compose(0L));
        assertEquals(5, renders.get());
    }

    @Test
    void multiLineJoinedInputKeepsNewlines() {
        AnimationTemplate template = compile("top\n|animation.fast|\nbottom",
            Set.of(), Map.of("animation.fast", FAST), IDENTITY_RENDERER);

        assertEquals("top\nA\nbottom", template.compose(0L));
    }

    @Test
    void composeReturnsTheCachedStringWhileFrameIndicesAreUnchanged() {
        AnimationTemplate template = compile("x |animation.fast| y", Set.of(), Map.of("animation.fast", FAST), IDENTITY_RENDERER);
        String first = template.compose(0L);
        String second = template.compose(5L);
        String third = template.compose(9L);
        String changed = template.compose(10L);
        String back = template.compose(40L);

        assertSame(first, second);
        assertSame(first, third);
        assertEquals("x B y", changed);
        assertEquals("x A y", back);
        assertNotSame(first, back);
    }

    @Test
    void emptyFrameListComposesToNothing() {
        AnimationClip empty = new AnimationClip("empty", 100.0D, AnimationMode.ASCEND, List.of());
        AnimationTemplate template = compile("x|animation.empty|y",
            Set.of(), Map.of("animation.empty", empty), IDENTITY_RENDERER);

        assertTrue(template.hasSlots());
        assertEquals("xy", template.compose(0L));
    }
}
