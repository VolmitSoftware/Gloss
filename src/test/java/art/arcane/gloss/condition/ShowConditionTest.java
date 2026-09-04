package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowConditionTest {
    @Test
    void booleansRoundTripWithoutAnObjectWrapper() {
        assertEquals(ShowCondition.ALWAYS, parse("true"));
        assertEquals(ShowCondition.NEVER, parse("false"));
        assertEquals("true", BukkitJson.GSON.toJson(ShowCondition.ALWAYS));
        assertEquals("false", BukkitJson.GSON.toJson(ShowCondition.NEVER));
        assertTrue(ShowCondition.ALWAYS.matches(null));
        assertFalse(ShowCondition.NEVER.matches(null));
    }

    @Test
    void worldAndTimeAreReevaluatedAcrossBothTransitions() {
        ShowCondition show = parse("\"world.name == 'survival' && world.time > 12000\"");
        assertFalse(show.matches(scope("survival", 12000)));
        assertTrue(show.matches(scope("survival", 12001)));
        assertFalse(show.matches(scope("lobby", 13000)));
        assertTrue(show.matches(scope("survival", 13000)));
        assertFalse(show.matches(scope("survival", 1000)));
        assertEquals(show, parse(BukkitJson.GSON.toJson(show)));
    }

    @Test
    void expressionsCanUseBooleanVariablesAndDelimiters() {
        ShowCondition show = ShowCondition.of("{{ viewer.sneaking }}");
        assertTrue(show.matches(new Values(Map.of("viewer.sneaking", true))));
        assertFalse(show.matches(new Values(Map.of("viewer.sneaking", false))));
        assertEquals("viewer.sneaking", show.expression());
    }

    @Test
    void invalidTypesAndNonBooleanExpressionsFailAtLoad() {
        for (String json : List.of("1", "{}", "[]", "\"\"", "\"world.time + 1\"", "\"world.time >\"")) {
            assertThrows(RuntimeException.class, () -> parse(json), json);
        }
    }

    @Test
    void missingVariablesAndWrongRuntimeTypesHideTheDisplay() {
        ShowCondition show = ShowCondition.of("viewer.sneaking");
        BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.bounded(2, error -> {
        });
        assertFalse(show.matches(new Values(Map.of()), errors));
        assertFalse(show.matches(new Values(Map.of("viewer.sneaking", "true")), errors));
        assertEquals(2, errors.reportCount());
    }

    private static ShowCondition parse(String json) {
        return BukkitJson.GSON.fromJson(json, ShowCondition.class);
    }

    private static ExprScope scope(String world, double time) {
        return new Values(Map.of("world.name", world, "world.time", time));
    }

    private record Values(Map<String, Object> values) implements ExprScope {
        @Override
        public Object variable(String name) {
            return values.get(name);
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return ExprFunctions.call(name, arguments);
        }
    }
}
