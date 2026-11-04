package art.arcane.gloss.menu.components;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToggleInitialStateTest {
    private Gloss previous;

    @BeforeEach
    void installText() throws ReflectiveOperationException {
        Gloss gloss = CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of()));
        CharacterizationSupport.setField(gloss, "text", new TextPipeline(gloss));
        previous = CharacterizationSupport.installGloss(gloss);
    }

    @AfterEach
    void restore() {
        CharacterizationSupport.restoreGloss(previous);
    }

    @Test
    void theConditionReadsTheComponentScope() {
        assertTrue(ToggleComponent.initialState(null, scope(Map.of("args.mode", "on")), "{{ args.mode }}", "on"));
        assertFalse(ToggleComponent.initialState(null, scope(Map.of("args.mode", "off")), "{{ args.mode }}", "on"));
    }

    @Test
    void theExpectedValueReadsTheComponentScope() {
        assertTrue(ToggleComponent.initialState(null, scope(Map.of("args.want", "ON")), "on", "{{ args.want }}"));
    }

    @Test
    void aMissingExpectedValueIsFalse() {
        assertFalse(ToggleComponent.initialState(null, scope(Map.of()), "on", null));
    }

    private static ExprScope scope(Map<String, Object> values) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return values.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return null;
            }
        };
    }
}
