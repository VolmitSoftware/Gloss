package art.arcane.gloss.command;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprCommandTest {
    private static ExprScope scope(Map<String, Object> values) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return values.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }
        };
    }

    @Test
    void reportsTheValueAndItsJavaType() {
        CommandGlossExpr.ExprReport number = CommandGlossExpr.report("2 + 3 * 4", scope(Map.of()));
        assertNull(number.error());
        assertEquals("14", number.value());
        assertEquals("Double", number.type());

        CommandGlossExpr.ExprReport text = CommandGlossExpr.report("'a' + 'b'", scope(Map.of()));
        assertEquals("ab", text.value());
        assertEquals("String", text.type());

        CommandGlossExpr.ExprReport flag = CommandGlossExpr.report("1 < 2", scope(Map.of()));
        assertEquals("true", flag.value());
        assertEquals("Boolean", flag.type());
    }

    @Test
    void namesTheResolverThatAnsweredEachVariable() {
        ExprVariableNamespaces.global().register(new ProbeNamespace());
        try {
            CommandGlossExpr.ExprReport report = CommandGlossExpr.report(
                "probe.level + level + missing.thing", scope(Map.of("level", 2.0D)));

            assertEquals(List.of("level", "missing.thing", "probe.level"),
                report.references().stream().map(CommandGlossExpr.Reference::name).toList());
            assertEquals(List.of("scope", "unresolved", "namespace probe"),
                report.references().stream().map(CommandGlossExpr.Reference::resolver).toList());
            assertEquals(List.of("2", "-", "5"),
                report.references().stream().map(CommandGlossExpr.Reference::value).toList());
        } finally {
            ExprVariableNamespaces.global().unregister(ProbeNamespace.PREFIX);
        }
    }

    @Test
    void listsTheFunctionsTheExpressionCalls() {
        CommandGlossExpr.ExprReport report = CommandGlossExpr.report("max(1, min(4, 2))", scope(Map.of()));

        assertEquals(List.of("max", "min"), report.functions());
        assertEquals("2", report.value());
    }

    @Test
    void reportsTheParseOrEvaluationFailureInsteadOfThrowing() {
        CommandGlossExpr.ExprReport broken = CommandGlossExpr.report("2 +", scope(Map.of()));
        assertNotNull(broken.error());
        assertNull(broken.value());

        CommandGlossExpr.ExprReport unresolved = CommandGlossExpr.report("nope + 1", scope(Map.of()));
        assertNotNull(unresolved.error());
        assertTrue(unresolved.references().stream().anyMatch(reference -> reference.name().equals("nope")));
    }

    private static final class ProbeNamespace implements ExprVariableNamespace {
        private static final String PREFIX = "probe";

        @Override
        public String prefix() {
            return PREFIX;
        }

        @Override
        public Object resolve(String suffix, ExprVariableContext context) {
            return suffix.equals("level") ? 5.0D : null;
        }
    }
}
