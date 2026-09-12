package art.arcane.gloss.rig;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigStateMachineTest {
    private static final ExprScope SCOPE = new ExprScope() {
        @Override
        public Object variable(String dottedName) {
            return ExprVariableNamespaces.global().resolve(dottedName, ExprVariableContext.empty());
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    };

    @BeforeAll
    static void registerNamespace() {
        ExprVariableNamespaces.global().register(new RigNamespace());
    }

    @AfterAll
    static void unregisterNamespace() {
        ExprVariableNamespaces.global().unregister(RigNamespace.PREFIX);
    }

    private static RigDoc doc() {
        return RigDoc.parse("pedestal.json", RigDocTest.PEDESTAL);
    }

    @Test
    void transitionsFireWhenTheirConditionMatchesAndVarsResolveThroughTheNamespace() {
        RigStateMachine machine = new RigStateMachine("altar", CompiledGraph.compile(doc().graph()), Map.of("opened", Boolean.FALSE));
        assertEquals("idle", machine.state());
        assertEquals("idle", machine.clip());

        assertFalse(machine.step(SCOPE, false));
        machine.setVar("opened", Boolean.TRUE);
        assertTrue(machine.step(SCOPE, false));
        assertEquals("open", machine.state());
        assertEquals("open", machine.clip());
    }

    @Test
    void thenChainsWhenAOnceClipEnds() {
        RigStateMachine machine = new RigStateMachine("altar", CompiledGraph.compile(doc().graph()), Map.of("opened", Boolean.TRUE));
        assertTrue(machine.step(SCOPE, false));
        assertEquals("open", machine.state());
        machine.setVar("opened", Boolean.FALSE);
        assertFalse(machine.step(SCOPE, false));
        assertTrue(machine.step(SCOPE, true));
        assertEquals("idle", machine.state());
    }

    @Test
    void namespaceExposesStateInstanceAndVars() {
        RigStateMachine machine = new RigStateMachine("altar", CompiledGraph.compile(doc().graph()), Map.of("opened", Boolean.FALSE, "count", 3.0D));
        Object state = RigNamespace.with(machine, () -> SCOPE.variable("rig.state"));
        Object instance = RigNamespace.with(machine, () -> SCOPE.variable("rig.instance"));
        Object count = RigNamespace.with(machine, () -> SCOPE.variable("rig.var.count"));
        assertEquals("idle", state);
        assertEquals("altar", instance);
        assertEquals(3.0D, count);
        assertNull(SCOPE.variable("rig.state"));
    }

    @Test
    void setStateSwitchesOnlyToKnownStatesAndAGraphlessRigStaysStateless() {
        RigStateMachine machine = new RigStateMachine("altar", CompiledGraph.compile(doc().graph()), Map.of());
        assertFalse(machine.setState("nowhere"));
        assertTrue(machine.setState("open"));
        assertEquals("open", machine.state());
        RigStateMachine stateless = new RigStateMachine("bare", CompiledGraph.compile(null), Map.of());
        assertNull(stateless.state());
        assertNull(stateless.clip());
        assertFalse(stateless.step(SCOPE, true));
    }
}
