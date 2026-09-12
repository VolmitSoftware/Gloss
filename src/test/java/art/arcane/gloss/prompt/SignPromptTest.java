package art.arcane.gloss.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a sign editor gives back. The client returns four lines whatever the player typed, so the
 * join is what decides whether a blank second line becomes a double space in the answer.
 */
class SignPromptTest {

    @Test
    void theFourLinesJoinIntoOneAnswer() {
        assertEquals("hello there", SignPrompt.join(new String[]{"hello", "there", "", ""}));
        assertEquals("a b c d", SignPrompt.join(new String[]{"a", "b", "c", "d"}));
    }

    @Test
    void blankLinesInTheMiddleDoNotDoubleTheSpacing() {
        assertEquals("hello there", SignPrompt.join(new String[]{"hello", "", "there", ""}));
        assertEquals("hello", SignPrompt.join(new String[]{"", "hello", "  ", ""}));
    }

    @Test
    void anEmptySignIsAnEmptyAnswer() {
        assertEquals("", SignPrompt.join(new String[]{"", "", "", ""}));
        assertEquals("", SignPrompt.join(new String[0]));
        assertEquals("", SignPrompt.join(null));
    }

    @Test
    void theEditorSitsWellBelowThePlayerSoNoRealBlockIsTouched() {
        assertEquals(-3, SignPrompt.EDITOR_Y_OFFSET);
    }
}
