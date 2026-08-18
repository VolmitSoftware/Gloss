package art.arcane.gloss.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPipelineFunctionTest {
    @Test
    void registeredFunctionTokenIsReplaced() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("greet", player -> "World");
        assertEquals("Hello World!", pipeline.renderStatic("Hello |greet|!"));
    }

    @Test
    void unknownTokenIsLeftIntact() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("known", player -> "value");
        assertEquals("Hello |nope|!", pipeline.renderStatic("Hello |nope|!"));
    }

    @Test
    void unknownAndKnownTokensMix() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("b", player -> "B");
        assertEquals("|a| B", pipeline.renderStatic("|a| |b|"));
    }

    @Test
    void adjacentTokensBothReplace() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("x", player -> "1");
        pipeline.registerFunction("y", player -> "2");
        assertEquals("12", pipeline.renderStatic("|x||y|"));
    }

    @Test
    void staticRenderPassesNullPlayerToResolver() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("who", player -> player == null ? "static" : "player");
        assertEquals("static", pipeline.renderStatic("|who|"));
    }

    @Test
    void unregisteredFunctionTokenStaysIntact() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("gone", player -> "value");
        pipeline.unregisterFunction("gone");
        assertEquals("|gone|", pipeline.renderStatic("|gone|"));
    }

    @Test
    void failingResolverCollapsesToEmpty() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("boom", player -> {
            throw new IllegalStateException("boom");
        });
        assertEquals("ab", pipeline.renderStatic("a|boom|b"));
    }

    @Test
    void nullResolverValueCollapsesToEmpty() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("none", player -> null);
        assertEquals("ab", pipeline.renderStatic("a|none|b"));
    }

    @Test
    void dottedFunctionNamesResolve() {
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("animation.rainbow", player -> "frame");
        assertEquals("frame", pipeline.renderStatic("|animation.rainbow|"));
    }

    @Test
    void placeholdersAreSkippedWithoutViewer() {
        TextPipeline pipeline = new TextPipeline(null);
        assertEquals("%player_name%", pipeline.renderStatic("%player_name%"));
    }

    @Test
    void nullInputRendersEmpty() {
        TextPipeline pipeline = new TextPipeline(null);
        assertEquals("", pipeline.renderStatic(null));
    }
}
