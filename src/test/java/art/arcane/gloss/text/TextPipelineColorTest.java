package art.arcane.gloss.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPipelineColorTest {
    private final TextPipeline pipeline = new TextPipeline(null);

    @Test
    void bracketHexTranslatesToSectionXSequence() {
        assertEquals("§x§f§f§0§0§a§aHi", pipeline.renderStatic("[FF00AA]Hi"));
    }

    @Test
    void bracketHexAcceptsLowercase() {
        assertEquals("§x§f§f§0§0§a§aHi", pipeline.renderStatic("[ff00aa]Hi"));
    }

    @Test
    void multipleBracketHexSequencesTranslate() {
        assertEquals(
            "§x§f§f§0§0§0§0A§x§0§0§f§f§0§0B",
            pipeline.renderStatic("[FF0000]A[00FF00]B"));
    }

    @Test
    void shortBracketContentIsUntouched() {
        assertEquals("[FF00A]X", pipeline.renderStatic("[FF00A]X"));
    }

    @Test
    void nonHexBracketContentIsUntouched() {
        assertEquals("[GG0011]X", pipeline.renderStatic("[GG0011]X"));
    }

    @Test
    void unterminatedBracketIsUntouched() {
        assertEquals("[FF00AA", pipeline.renderStatic("[FF00AA"));
    }

    @Test
    void emptyBracketsAreUntouched() {
        assertEquals("[]", pipeline.renderStatic("[]"));
    }

    @Test
    void sevenDigitBracketIsUntouched() {
        assertEquals("[FFF00AA]", pipeline.renderStatic("[FFF00AA]"));
    }

    @Test
    void ampersandCodesTranslate() {
        assertEquals("§cHi", pipeline.renderStatic("&cHi"));
    }

    @Test
    void alreadyTranslatedSectionTextPassesThrough() {
        assertEquals("§bHi", pipeline.renderStatic("§bHi"));
    }

    @Test
    void bracketHexAndAmpersandCombine() {
        assertEquals("§x§f§f§0§0§a§a§lBold", pipeline.renderStatic("[FF00AA]&lBold"));
    }

    @Test
    void sharedHexSpellingsRenderIdentically() {
        String expected = "§x§f§f§0§0§a§aHi";

        assertEquals(expected, pipeline.renderStatic("&#FF00AAHi"));
        assertEquals(expected, pipeline.renderStatic("&xFF00AAHi"));
        assertEquals(expected, pipeline.renderStatic("&x&F&F&0&0&A&AHi"));
    }

    @Test
    void plainTextIsUnchanged() {
        assertEquals("plain text", pipeline.renderStatic("plain text"));
    }
}
