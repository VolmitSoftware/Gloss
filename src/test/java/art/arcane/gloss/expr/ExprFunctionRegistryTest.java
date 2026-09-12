package art.arcane.gloss.expr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprFunctionRegistryTest {
    private static final ExprScope EMPTY = new ExprScope() {
        @Override
        public Object variable(String dottedName) {
            return null;
        }

        @Override
        public Object call(String name, List<Object> args) {
            return null;
        }
    };

    @AfterEach
    void cleanUp() {
        ExprFunctionRegistry.global().unregister("double");
        ExprFunctionRegistry.global().unregister("joinAll");
    }

    @Test
    void registeredFunctionIsCallableAndSupported() {
        ExprFunctionRegistry.global().register(new ExprFunctionRegistry.Spec("double",
            ExprFunctionRegistry.Kind.NUMBER, List.of(ExprFunctionRegistry.Kind.NUMBER), false,
            (scope, args) -> ((Double) args.get(0)) * 2.0D));

        assertTrue(ExprFunctions.isSupported("double"));
        assertFalse(ExprFunctions.isBuiltIn("double"));
        assertEquals(8.0D, ExprFunctionRegistry.global().call(EMPTY, "double", List.of(4.0D)));
    }

    @Test
    void unknownNamesResolveToNullSoScopesKeepTheirOwnFallbacks() {
        assertNull(ExprFunctionRegistry.global().call(EMPTY, "nothing", List.of()));
    }

    @Test
    void arityIsEnforcedForFixedAndVariadicSignatures() {
        ExprFunctionRegistry.global().register(new ExprFunctionRegistry.Spec("double",
            ExprFunctionRegistry.Kind.NUMBER, List.of(ExprFunctionRegistry.Kind.NUMBER), false,
            (scope, args) -> 0.0D));
        ExprFunctionRegistry.global().register(new ExprFunctionRegistry.Spec("joinAll",
            ExprFunctionRegistry.Kind.STRING, List.of(ExprFunctionRegistry.Kind.STRING, ExprFunctionRegistry.Kind.ANY), true,
            (scope, args) -> String.valueOf(args.size())));

        assertThrows(ExprException.class, () -> ExprFunctionRegistry.global().call(EMPTY, "double", List.of()));
        assertEquals("1", ExprFunctionRegistry.global().call(EMPTY, "joinAll", List.of("a")));
        assertEquals("3", ExprFunctionRegistry.global().call(EMPTY, "joinAll", List.of("a", 1.0D, true)));
        assertThrows(ExprException.class, () -> ExprFunctionRegistry.global().call(EMPTY, "joinAll", List.of()));
    }

    @Test
    void builtInsDottedNamesAndDuplicatesAreRejected() {
        ExprFunctionRegistry.Implementation noop = (scope, args) -> null;
        assertThrows(IllegalArgumentException.class, () -> ExprFunctionRegistry.global().register(
            new ExprFunctionRegistry.Spec("clamp", ExprFunctionRegistry.Kind.NUMBER, List.of(), false, noop)));
        assertThrows(IllegalArgumentException.class, () -> new ExprFunctionRegistry.Spec(
            "state.get", ExprFunctionRegistry.Kind.NUMBER, List.of(), false, noop));
        ExprFunctionRegistry.global().register(new ExprFunctionRegistry.Spec("double",
            ExprFunctionRegistry.Kind.NUMBER, List.of(), false, noop));
        assertThrows(IllegalArgumentException.class, () -> ExprFunctionRegistry.global().register(
            new ExprFunctionRegistry.Spec("double", ExprFunctionRegistry.Kind.NUMBER, List.of(), false, noop)));
    }
}
