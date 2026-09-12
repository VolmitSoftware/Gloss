package art.arcane.gloss.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateDeclarationsTest {
    @Test
    void agreeingDeclarationsAcrossDocumentsMergeIntoOneSchemaPerKey() {
        StateSchema visits = new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0);
        StateDeclarations merged = StateDeclarations.merge(Map.of(
            "welcome", List.of(visits, new StateSchema("event", StateScope.GLOBAL, StateType.STRING, "none")),
            "quests", List.of(new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0.0D))));

        assertEquals(Set.of("visits", "event"), merged.keys());
        assertEquals(StateScope.PLAYER, merged.get("visits").scope());
        assertEquals(0.0D, merged.get("visits").defaultValue());
        assertEquals("none", merged.get("event").defaultValue());
        assertNull(merged.get("missing"));
        assertTrue(StateDeclarations.empty().keys().isEmpty());
    }

    @Test
    void differingTypeScopeOrDefaultNamesBothDocuments() {
        StateSchema number = new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0);
        StateConflictException type = assertThrows(StateConflictException.class, () -> StateDeclarations.merge(Map.of(
            "a", List.of(number),
            "b", List.of(new StateSchema("visits", StateScope.PLAYER, StateType.STRING, "0")))));
        assertEquals("visits", type.key());
        assertEquals(Set.of("a", "b"), Set.of(type.documentA(), type.documentB()));
        assertTrue(type.getMessage().contains("visits"));

        assertThrows(StateConflictException.class, () -> StateDeclarations.merge(Map.of(
            "a", List.of(number),
            "b", List.of(new StateSchema("visits", StateScope.GLOBAL, StateType.NUMBER, 0)))));
        assertThrows(StateConflictException.class, () -> StateDeclarations.merge(Map.of(
            "a", List.of(number),
            "b", List.of(new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 1)))));
    }

    @Test
    void duplicateKeyInsideOneDocumentIsAConflictToo() {
        assertThrows(StateConflictException.class, () -> StateDeclarations.merge(Map.of(
            "a", List.of(new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0),
                new StateSchema("visits", StateScope.PLAYER, StateType.BOOLEAN, false)))));
    }

    @Test
    void schemaValidatesKeysAndCoercesDefaults() {
        assertEquals(0.0D, new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, null).defaultValue());
        assertEquals("", new StateSchema("event", StateScope.GLOBAL, StateType.STRING, null).defaultValue());
        assertEquals(false, new StateSchema("welcomed", StateScope.PLAYER, StateType.BOOLEAN, null).defaultValue());
        assertEquals(3.0D, new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, "3").defaultValue());
        assertEquals(true, new StateSchema("welcomed", StateScope.PLAYER, StateType.BOOLEAN, "true").defaultValue());
        assertEquals("4", new StateSchema("label", StateScope.WORLD, StateType.STRING, 4.0D).defaultValue());
        assertThrows(IllegalArgumentException.class, () -> new StateSchema("Visits", StateScope.PLAYER, StateType.NUMBER, 0));
        assertThrows(IllegalArgumentException.class, () -> new StateSchema("1st", StateScope.PLAYER, StateType.NUMBER, 0));
        assertThrows(IllegalArgumentException.class, () -> new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, "many"));
        assertThrows(IllegalArgumentException.class, () -> new StateSchema("welcomed", StateScope.PLAYER, StateType.BOOLEAN, "maybe"));
    }

    @Test
    void scopeAndTypeParseTheirDocumentSpellings() {
        assertEquals(StateScope.PLAYER, StateScope.parse("player"));
        assertEquals(StateScope.GLOBAL, StateScope.parse("Global"));
        assertEquals(StateType.BOOLEAN, StateType.parse("boolean"));
        assertThrows(IllegalArgumentException.class, () -> StateScope.parse("server"));
        assertThrows(IllegalArgumentException.class, () -> StateType.parse("list"));
    }
}
