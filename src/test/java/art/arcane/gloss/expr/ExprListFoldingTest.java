package art.arcane.gloss.expr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Animated board lines evaluate the same literal palette on every frame for every viewer. A list
 * of literals cannot change between evaluations, so it is folded once at parse time and shared.
 */
class ExprListFoldingTest {
    @Test
    void aLiteralListIsSharedAcrossEvaluations() {
        Expr expression = ExprParser.parse("['&c','&6','&e']");
        MapScope scope = new MapScope(Map.of());

        Object first = ExprEvaluator.eval(expression, scope);
        Object second = ExprEvaluator.eval(expression, scope);

        assertEquals(List.of("&c", "&6", "&e"), first);
        assertSame(first, second);
    }

    @Test
    void mixedNumberAndBooleanLiteralsFoldToTheSameValuesTheEvaluatorWouldProduce() {
        Expr expression = ExprParser.parse("[1, 2.5, true, 'x']");

        assertEquals(List.of(1.0D, 2.5D, Boolean.TRUE, "x"), ExprEvaluator.eval(expression, new MapScope(Map.of())));
    }

    @Test
    void aListWithADynamicElementIsStillEvaluatedPerCall() {
        Expr expression = ExprParser.parse("['&c', x]");
        MapScope first = new MapScope(Map.of("x", 1.0D));
        MapScope second = new MapScope(Map.of("x", 2.0D));

        Object one = ExprEvaluator.eval(expression, first);
        Object two = ExprEvaluator.eval(expression, second);

        assertEquals(List.of("&c", 1.0D), one);
        assertEquals(List.of("&c", 2.0D), two);
        assertNotSame(one, two);
    }

    @Test
    void aFoldedListStillDrivesTheSelectFunction() {
        Expr expression = ExprParser.parse("select(['&c','&6','&e'], 4)");

        assertEquals("&6", ExprEvaluator.string(expression, new MapScope(Map.of())));
    }

    @Test
    void parsedListsStillCompareByContent() {
        assertEquals(ExprParser.parse("[1, 2]"), ExprParser.parse("[1, 2]"));
        assertEquals(ExprParser.parse("[1, 2]").hashCode(), ExprParser.parse("[1, 2]").hashCode());
    }
}
