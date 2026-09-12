package art.arcane.gloss.chat;

import art.arcane.gloss.forge.GlyphLedger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The body and the hover card reach the rendered format as private markers. A player's own text
 * arrives with them, so the splice has to be one pass and the markers have to be characters no
 * client can send and no forged glyph can occupy.
 */
class ChatMarkerSpliceTest {

    @Test
    void aBodyCarryingTheCardMarkerDoesNotPullTheCardIntoItself() {
        String spliced = ChatMessageRenderer.splice(
            "<white>" + ChatMessageRenderer.MESSAGE_MARKER + "|" + ChatMessageRenderer.CARD_MARKER,
            "hello " + ChatMessageRenderer.CARD_MARKER + " there", "THE-CARD");

        assertEquals("<white>hello " + ChatMessageRenderer.CARD_MARKER + " there|THE-CARD", spliced);
    }

    @Test
    void aFormatWithNoMarkersIsUntouched() {
        assertEquals("<white>plain", ChatMessageRenderer.splice("<white>plain", "body", "card"));
    }

    @Test
    void theMarkersSitOutsideTheRangeTheGlyphForgeAllocatesFrom() {
        String markers = ChatMessageRenderer.MESSAGE_MARKER + ChatMessageRenderer.CARD_MARKER;

        assertTrue(markers.chars().noneMatch(
            codepoint -> codepoint >= GlyphLedger.MIN_BASE && codepoint <= GlyphLedger.MAX_CODEPOINT),
            "a forged glyph would collide with the renderer's markers");
    }
}
