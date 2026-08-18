package art.arcane.gloss.emoji;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnicodeTextTest {
    @Test
    void singleCodepointParses() {
        assertEquals("❤", UnicodeText.parse("U+2764;"));
    }

    @Test
    void multipleCodepointsConcatenate() {
        assertEquals("❤️", UnicodeText.parse("U+2764;U+FE0F;"));
    }

    @Test
    void supplementaryCodepointExpandsToSurrogatePair() {
        assertEquals("😀", UnicodeText.parse("U+1F600;"));
    }

    @Test
    void codepointEmbeddedInTextParses() {
        assertEquals("a ❤ b", UnicodeText.parse("a U+2764; b"));
    }

    @Test
    void invalidHexBecomesQuestionMark() {
        assertEquals("?", UnicodeText.parse("U+ZZZZ;"));
    }

    @Test
    void outOfRangeCodepointBecomesQuestionMark() {
        assertEquals("?", UnicodeText.parse("U+110000;"));
    }

    @Test
    void unterminatedValidEscapeParsesAtEndOfInput() {
        assertEquals("❤", UnicodeText.parse("U+2764"));
    }

    @Test
    void unterminatedValidEscapeAfterTextParses() {
        assertEquals("a ❤", UnicodeText.parse("a U+2764"));
    }

    @Test
    void unterminatedInvalidEscapeIsReEmittedLiterally() {
        assertEquals("a U+ZZZZ", UnicodeText.parse("a U+ZZZZ"));
    }

    @Test
    void trailingEscapePrefixIsReEmittedLiterally() {
        assertEquals("a U+", UnicodeText.parse("a U+"));
    }

    @Test
    void plainTextWithUppercaseUPassesThrough() {
        assertEquals("Universe", UnicodeText.parse("Universe"));
    }

    @Test
    void nullParsesToEmpty() {
        assertEquals("", UnicodeText.parse(null));
    }

    @Test
    void emptyParsesToEmpty() {
        assertEquals("", UnicodeText.parse(""));
    }
}
