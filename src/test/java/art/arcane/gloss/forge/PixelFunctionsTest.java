package art.arcane.gloss.forge;

import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelFunctionsTest {
    private static final SpaceGlyphs FAKE_SPACES = px -> "<s" + px + ">";

    private final ExprFunctionRegistry registry = new ExprFunctionRegistry();
    private final PixelFunctions functions = new PixelFunctions(FontMetrics.load(), () -> FAKE_SPACES);

    private static ExprScope scope(boolean packLoaded) {
        Map<String, Object> variables = Map.of("pack.loaded", packLoaded);
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return variables.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return null;
            }
        };
    }

    @AfterEach
    void cleanUp() {
        for (String name : PixelFunctions.NAMES) {
            ExprFunctionRegistry.global().unregister(name);
        }
    }

    @Test
    void pxAndPxWidthMeasureText() {
        functions.register(registry);
        assertEquals(24.0D, registry.call(scope(false), "px", List.of("Hello")));
        assertEquals(24.0D, registry.call(scope(false), "pxWidth", List.of("Hello")));
        assertEquals(29.0D, registry.call(scope(false), "px", List.of("&lHello")));
    }

    @Test
    void pxPadRoundsToFourPixelSpacesWithoutAPack() {
        functions.register(registry);
        assertEquals("Hi   ", registry.call(scope(false), "pxPad", List.of("Hi", 20.0D)));
        assertEquals("Hi   ", registry.call(scope(false), "pxPad", List.of("Hi", 18.0D)));
        assertEquals("Hi", registry.call(scope(false), "pxPad", List.of("Hi", 8.0D)));
        assertEquals("Hello", registry.call(scope(false), "pxPad", List.of("Hello", 4.0D)));
    }

    @Test
    void pxPadUsesExactSpaceGlyphsForPackViewers() {
        functions.register(registry);
        assertEquals("Hi<s12>", registry.call(scope(true), "pxPad", List.of("Hi", 20.0D)));
        assertEquals("Hi<s10>", registry.call(scope(true), "pxPad", List.of("Hi", 18.0D)));
    }

    @Test
    void pxAlignPlacesTextInsideTheWidth() {
        functions.register(registry);
        assertEquals("Hi    ", registry.call(scope(false), "pxAlign", List.of("Hi", 24.0D, "left")));
        assertEquals("    Hi", registry.call(scope(false), "pxAlign", List.of("Hi", 24.0D, "right")));
        assertEquals("  Hi  ", registry.call(scope(false), "pxAlign", List.of("Hi", 24.0D, "center")));
        assertEquals("<s8>Hi<s8>", registry.call(scope(true), "pxAlign", List.of("Hi", 24.0D, "center")));
        assertThrows(RuntimeException.class, () -> registry.call(scope(false), "pxAlign", List.of("Hi", 24.0D, "up")));
    }

    @Test
    void columnAlignsEveryCell() {
        functions.register(registry);
        Object row = registry.call(scope(false), "column",
            List.of(List.of("a", "b"), List.of(12.0D, 12.0D), List.of("left", "right")));
        assertEquals("a    b", row);
        Object defaults = registry.call(scope(false), "column",
            List.of(List.of("a", 7.0D), List.of(12.0D, 12.0D), List.of()));
        assertEquals("a  7  ", defaults);
        assertThrows(RuntimeException.class, () -> registry.call(scope(false), "column",
            List.of(List.of("a"), List.of(), List.of())));
    }

    @Test
    void registersInTheGlobalRegistryWithSignatures() {
        functions.register(ExprFunctionRegistry.global());
        for (String name : PixelFunctions.NAMES) {
            assertTrue(ExprFunctionRegistry.isSupported(name), name);
        }
        assertEquals(ExprFunctionRegistry.Kind.NUMBER, ExprFunctionRegistry.global().find("px").returns());
        assertEquals(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.global().find("column").returns());
        assertEquals(List.of(ExprFunctionRegistry.Kind.LIST, ExprFunctionRegistry.Kind.LIST, ExprFunctionRegistry.Kind.LIST),
            ExprFunctionRegistry.global().find("column").parameters());
    }
}
