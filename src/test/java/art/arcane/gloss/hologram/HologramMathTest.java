package art.arcane.gloss.hologram;

import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramMathTest {
    private static boolean placeholderRouted(List<String> lines) {
        return TextPipeline.viewerDependent(String.join("\n", lines));
    }

    @Test
    void viewRangeMultiplierScalesAgainstPaperBase() {
        assertEquals(0.75F, HologramMath.viewRangeMultiplier(48.0D), 1.0E-6F);
        assertEquals(1.0F, HologramMath.viewRangeMultiplier(64.0D), 1.0E-6F);
        assertEquals(2.0F, HologramMath.viewRangeMultiplier(128.0D), 1.0E-6F);
        assertEquals(0.0625F, HologramMath.viewRangeMultiplier(4.0D), 1.0E-6F);
    }

    @Test
    void classifyDetectsPlaceholderLines() {
        assertTrue(placeholderRouted(List.of("&7Hello", "%player_name%")));
        assertTrue(placeholderRouted(List.of("Hello {{ player.name }}")));
        assertTrue(placeholderRouted(List.of("|animation.rainbow|")));
        assertFalse(placeholderRouted(List.of("100% pure")));
    }

    @Test
    void classifyIgnoresStaticLines() {
        assertFalse(placeholderRouted(List.of()));
        assertFalse(placeholderRouted(List.of("&dWelcome", "&7No tokens here")));
    }

    @Test
    void classifyUnionsFlagsAcrossLines() {
        int flags = HologramMath.classify(List.of("&aplain", "|clock|", "%hp%"));

        assertEquals(TextPipeline.HAS_COLOR | TextPipeline.HAS_FUNCTION | TextPipeline.HAS_PLACEHOLDER,
            flags & (TextPipeline.HAS_COLOR | TextPipeline.HAS_FUNCTION | TextPipeline.HAS_PLACEHOLDER));
    }

    @Test
    void registeredFunctionDetectionIgnoresUnknownPipeText() {
        Predicate<String> registered = Set.of("clock", "foo")::contains;

        assertTrue(HologramMath.containsRegisteredFunction("time |clock| left", registered));
        assertFalse(HologramMath.containsRegisteredFunction("a | b", registered));
        assertFalse(HologramMath.containsRegisteredFunction("no pipes here", registered));
        assertFalse(HologramMath.containsRegisteredFunction("|unclosed clock", registered));
    }

    @Test
    void registeredFunctionDetectionMirrorsPipelineScanSemantics() {
        Predicate<String> registered = Set.of("foo")::contains;

        assertTrue(HologramMath.containsRegisteredFunction("a |foo|bar| b", registered));
        assertFalse(HologramMath.containsRegisteredFunction("|notfunc|other| b", registered));
        assertTrue(HologramMath.containsRegisteredFunction("|notfunc|foo| b", registered));
    }
}
