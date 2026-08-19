package art.arcane.gloss.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextExpressionRendererTest {
    private final TextExpressionRenderer renderer = new TextExpressionRenderer(null);

    @Test
    void evaluatesInlineMathAndTextFunctions() {
        assertEquals("TPS 19.8", renderer.render(null, "TPS {{ fixed(19.84, 1) }}"));
        assertEquals("[FF00AA]Live", renderer.render(null, "{{ hex(#FF00AA) }}Live"));
        assertEquals("[###-------]", renderer.render(null, "[{{ bar(3, 10, 10, '#', '-') }}]"));
        assertEquals("B", renderer.render(null, "{{ select(['A', 'B', 'C'], 4) }}"));
    }

    @Test
    void supportsTimeDrivenAuthoredAnimation() {
        String rendered = renderer.render(null, "{{ select(['&c', '&b'], floor(time.seconds * 4)) }}Pulse");
        assertTrue(rendered.equals("&cPulse") || rendered.equals("&bPulse"));
    }

    @Test
    void leavesInvalidOrUnclosedExpressionsEditable() {
        assertEquals("A {{ nope() }} B", renderer.render(null, "A {{ nope() }} B"));
        assertEquals("A {{ 1 + 2", renderer.render(null, "A {{ 1 + 2"));
    }

    @Test
    void leavesPapiLiteralWithoutAPlayer() {
        assertEquals("Hello %player_name%", renderer.render(null, "Hello {{ papi('player_name') }}"));
    }
}
