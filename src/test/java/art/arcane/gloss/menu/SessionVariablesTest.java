package art.arcane.gloss.menu;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu's own state. The generation counter is what tells the session which components need
 * re-rendering, so it only moves when a value actually changed.
 */
class SessionVariablesTest {

    @Test
    void declaredDefaultsAreVisibleBeforeAnythingIsSet() {
        SessionVariables variables = SessionVariables.of(Map.of("tab", "weapons", "page", 0.0D), Map.of());
        assertEquals("weapons", variables.get("tab"));
        assertEquals(0.0D, variables.get("page"));
        assertNull(variables.get("missing"));
    }

    @Test
    void argumentsWinOverDeclaredDefaults() {
        SessionVariables variables = SessionVariables.of(Map.of("tab", "weapons"), Map.of("tab", "armor"));
        assertEquals("armor", variables.get("tab"));
        assertEquals("armor", variables.args().get("tab"));
    }

    @Test
    void argumentsAreAlsoReadableOnTheirOwn() {
        SessionVariables variables = SessionVariables.of(Map.of(), Map.of("tier", "gold"));
        assertEquals(Map.of("tier", "gold"), variables.args());
        assertEquals("gold", variables.get("tier"));
    }

    @Test
    void settingAValueMovesTheGeneration() {
        SessionVariables variables = SessionVariables.of(Map.of("tab", "weapons"), Map.of());
        long before = variables.generation();

        assertTrue(variables.set("tab", "armor"));
        assertEquals("armor", variables.get("tab"));
        assertTrue(variables.generation() > before);
    }

    @Test
    void settingTheSameValueLeavesTheGenerationAlone() {
        SessionVariables variables = SessionVariables.of(Map.of("tab", "weapons"), Map.of());
        variables.set("tab", "armor");
        long after = variables.generation();

        assertTrue(!variables.set("tab", "armor"));
        assertEquals(after, variables.generation());
    }

    @Test
    void declaredVariableExpressionsFoldToConstants() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("tab", "'weapons'");
        declared.put("page", "1 + 1");
        declared.put("open", "true");

        Map<String, Object> defaults = SessionVariables.evaluateDeclarations(declared);
        assertEquals("weapons", defaults.get("tab"));
        assertEquals(2.0D, defaults.get("page"));
        assertEquals(Boolean.TRUE, defaults.get("open"));
    }

    @Test
    void aDeclarationThatIsNotConstantIsRefused() {
        Map<String, String> declared = Map.of("tab", "viewer.level");
        assertEquals(Map.of(), SessionVariables.evaluateDeclarations(declared),
            "a var that needs live state is not a document default");
    }
}
