package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprRoleSnapshot;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSnapshotScopeTest {
    @Test
    void combinesViewerValuesWithCapturedSubjectAndWorldValues() {
        Captured subject = new Captured(Map.of("subject.health", 4.0D, "subject.name", "Subject",
            "world.uuid", "subject-world"));
        RoleSnapshotScope scope = scope(subject);
        assertTrue(ConditionCompiler.compile("viewer.health > subject.health").matches(scope));
        assertEquals("Subject", scope.variable("subject.username"));
        assertEquals("subject-world", scope.variable("world.uuid"));
        assertEquals("viewer-world", scope.variable("viewer.world"));
    }

    @Test
    void roleProviderArgumentsCanDependOnTheOtherRolesCapturedValues() {
        Captured subject = new Captured(Map.of());
        RoleSnapshotScope scope = scope(subject);
        assertTrue(ConditionCompiler.compile("hasPermission('subject', 'rank.' + viewer.name)").matches(scope));
        assertEquals(List.of("subject", "rank.Viewer"), subject.arguments);
        assertEquals("hasPermission", subject.function);
    }

    @Test
    void customProviderReceivesTheCapturedRoleContextOnlyOnce() {
        Captured subject = new Captured(Map.of("subject.health", 4.0D));
        String function = "snapshotTestHealth";
        ExprFunctionRegistry registry = ExprFunctionRegistry.global();
        registry.register(new ExprFunctionRegistry.Spec(function, ExprFunctionRegistry.Kind.NUMBER,
            List.of(), false, (scope, args) -> scope.variableContext().roleValue("subject", "health")));
        try {
            assertEquals(4.0D, scope(subject).call(function, List.of()));
        } finally {
            registry.unregister(function);
        }
    }

    private static RoleSnapshotScope scope(Captured subject) {
        ExprScope viewer = new ExprScope() {
            @Override
            public Object variable(String name) {
                return switch (name) {
                    case "viewer.health" -> 20.0D;
                    case "viewer.name" -> "Viewer";
                    case "viewer.world" -> "viewer-world";
                    default -> throw new AssertionError("Unexpected live resolution: " + name);
                };
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                throw new AssertionError("Unexpected live provider call: " + name);
            }
        };
        return new RoleSnapshotScope(viewer, new ExprVariableContext(null, null, null, null,
            Map.of("subject", subject)));
    }

    private static final class Captured implements ExprRoleSnapshot {
        private final Map<String, Object> values;
        private String function;
        private List<Object> arguments;

        private Captured(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public Object variable(String property) {
            return values.get(property);
        }

        @Override
        public Object call(String name, List<Object> supplied) {
            function = name;
            arguments = List.copyOf(supplied);
            return true;
        }

        @Override
        public boolean ownsCurrentThread() {
            return false;
        }
    }
}
