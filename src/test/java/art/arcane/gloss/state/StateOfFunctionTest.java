package art.arcane.gloss.state;

import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionValidationException;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateOfFunctionTest {
    @TempDir
    Path folder;

    @AfterEach
    void cleanUp() {
        ExprFunctionRegistry.global().unregister(StateOfFunction.NAME);
    }

    private StateStore store() {
        StateStore store = new StateStore(folder, Runnable::run, (player, task) -> task.run());
        store.declare(StateTestSupport.declarationsByDocument());
        ExprFunctionRegistry.global().register(StateOfFunction.spec(store));
        return store;
    }

    private static ExprScope scope(Player viewer, Player subject, Player source) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return null;
            }

            @Override
            public Object call(String name, List<Object> args) {
                Object registered = ExprFunctionRegistry.global().call(this, name, args);
                return registered != null ? registered : ExprFunctions.call(name, args);
            }

            @Override
            public ExprVariableContext variableContext() {
                return new ExprVariableContext(viewer, subject, source, null);
            }
        };
    }

    @Test
    void stateOfReadsTheRoleEntityByTheKeysDeclaredScope() {
        StateStore store = store();
        World world = StateTestSupport.world(StateTestSupport.WORLD);
        Player viewer = StateTestSupport.player(StateTestSupport.PLAYER, world);
        Player subject = StateTestSupport.player(StateTestSupport.OTHER, world);
        store.beginLoad(StateTestSupport.PLAYER);
        store.beginLoad(StateTestSupport.OTHER);
        store.set(StateScope.PLAYER, StateTestSupport.PLAYER, "visits", 1);
        store.set(StateScope.PLAYER, StateTestSupport.OTHER, "visits", 9);
        store.set(StateScope.WORLD, StateTestSupport.WORLD, "weather", "storm");
        store.set(StateScope.GLOBAL, null, "event", "winter");
        ExprScope scope = scope(viewer, subject, null);

        assertEquals(1.0D, scope.call("stateOf", List.of("viewer", "visits")));
        assertEquals(9.0D, scope.call("stateOf", List.of("subject", "visits")));
        assertEquals("storm", scope.call("stateOf", List.of("subject", "weather")));
        assertEquals("winter", scope.call("stateOf", List.of("source", "event")));
        assertEquals(0.0D, scope.call("stateOf", List.of("source", "visits")));
        assertThrows(ExprException.class, () -> scope.call("stateOf", List.of("viewer", "unknown")));
        assertThrows(ExprException.class, () -> scope.call("stateOf", List.of("owner", "visits")));
    }

    @Test
    void conditionsTypeCheckAndEvaluateStateOfThroughTheRegistry() {
        StateStore store = store();
        World world = StateTestSupport.world(StateTestSupport.WORLD);
        Player viewer = StateTestSupport.player(StateTestSupport.PLAYER, world);
        Player subject = StateTestSupport.player(StateTestSupport.OTHER, world);
        store.beginLoad(StateTestSupport.OTHER);
        store.set(StateScope.PLAYER, StateTestSupport.OTHER, "visits", 5);
        CompiledCondition condition = ConditionCompiler.compile("stateOf('subject', 'visits') > 2");

        assertTrue(condition.matches(scope(viewer, subject, null)));
        assertFalse(condition.matches(scope(viewer, viewer, null)));
        assertThrows(ConditionValidationException.class, () -> ConditionCompiler.compile("stateOf('subject') > 2"));
        assertThrows(ConditionValidationException.class, () -> ConditionCompiler.compile("stateOf(1, 'visits') > 2"));
    }
}
