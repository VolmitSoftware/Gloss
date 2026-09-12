package art.arcane.gloss.expr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprVariableNamespacesTest {
    private static ExprVariableNamespace namespace(String prefix) {
        return new ExprVariableNamespace() {
            @Override
            public String prefix() {
                return prefix;
            }

            @Override
            public Object resolve(String suffix, ExprVariableContext context) {
                return suffix.equals("known") ? 42.0D : null;
            }
        };
    }

    @AfterEach
    void cleanUp() {
        ExprVariableNamespaces.global().unregister("teststate");
    }

    @Test
    void registeredPrefixResolvesSuffixesAndLeavesOthersUnknown() {
        ExprVariableNamespaces.global().register(namespace("teststate"));

        assertTrue(ExprVariableNamespaces.global().isRegistered("teststate"));
        assertEquals(42.0D, ExprVariableNamespaces.global().resolve("teststate.known", ExprVariableContext.empty()));
        assertNull(ExprVariableNamespaces.global().resolve("teststate.other", ExprVariableContext.empty()));
        assertNull(ExprVariableNamespaces.global().resolve("teststate", ExprVariableContext.empty()));
        assertNull(ExprVariableNamespaces.global().resolve("elsewhere.known", null));
    }

    @Test
    void reservedAndMalformedPrefixesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExprVariableNamespaces.global().register(namespace("viewer")));
        assertThrows(IllegalArgumentException.class, () -> ExprVariableNamespaces.global().register(namespace("vars")));
        assertThrows(IllegalArgumentException.class, () -> ExprVariableNamespaces.global().register(namespace("Bad.Name")));
        assertFalse(ExprVariableNamespaces.global().isRegistered("viewer"));
    }

    @Test
    void duplicateRegistrationFails() {
        ExprVariableNamespaces.global().register(namespace("teststate"));
        assertThrows(IllegalArgumentException.class, () -> ExprVariableNamespaces.global().register(namespace("teststate")));
    }
}
