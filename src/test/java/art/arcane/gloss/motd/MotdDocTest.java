package art.arcane.gloss.motd;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotdDocTest {
    @Test
    void parseReadsTheV2Shape() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 3,
              "entries": [
                {"lines": ["&dA glossy server"]},
                {"lines": ["&dLine one", "&7Line two"]}
              ]
            }
            """;

        MotdDoc doc = MotdDoc.parse("motd.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(3L, doc.revision());
        assertEquals(2, doc.entries().size());
        assertEquals(List.of("&dA glossy server"), doc.entries().get(0).lines());
        assertEquals("&dLine one§r\n&7Line two", doc.entries().get(1).joined());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        MotdDoc original = new MotdDoc(1, 4L, ShowCondition.ALWAYS, List.of(
            new MotdDoc.MotdEntry(List.of("&aHello")),
            new MotdDoc.MotdEntry(List.of("&aHello", "&7World"))));

        MotdDoc decoded = MotdDoc.parse("motd.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void missingAndNullShowDefaultToVisible() {
        MotdDoc missing = MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"entries":[{"lines":["Hello"]}]}
            """);
        MotdDoc explicitNull = MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"show":null,"entries":[{"lines":["Hello"]}]}
            """);

        assertTrue(missing.show().isAlwaysVisible());
        assertTrue(explicitNull.show().isAlwaysVisible());
    }

    @Test
    void booleanShowDoesNotRequireAPlayerOrServerScope() {
        MotdDoc hidden = MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"show":false,"entries":[{"lines":["Hello"]}]}
            """);

        assertFalse(hidden.show().matches((ExprScope) null));
        assertTrue(MotdDoc.DEFAULTS.show().matches((ExprScope) null));
        assertEquals(hidden, MotdDoc.parse("motd.json", BukkitJson.GSON.toJson(hidden)));
    }

    @Test
    void serverTimeShowIsReevaluatedForEachScope() {
        MotdDoc doc = MotdDoc.parse("motd.json", """
            {"schemaVersion":1,"revision":1,"show":"{{ time.hour >= 12 }}",
             "entries":[{"lines":["Afternoon"]}]}
            """);

        assertFalse(doc.show().matches(new TestScope(Map.of("time.hour", 11.0D))));
        assertTrue(doc.show().matches(new TestScope(Map.of("time.hour", 12.0D))));
        assertFalse(doc.show().matches(new TestScope(Map.of("time.hour", 0.0D))));
        assertEquals(doc, MotdDoc.parse("motd.json", BukkitJson.GSON.toJson(doc)));
    }

    @Test
    void legacyShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"entries\":[{\"lines\":[\"hi\"]}]}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> MotdDoc.parse("motd.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void revisionBoundsAreEnforced() {
        List<MotdDoc.MotdEntry> entries = List.of(new MotdDoc.MotdEntry(List.of("hi")));

        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 0L, ShowCondition.ALWAYS, entries));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, ShowCondition.ALWAYS, entries));
    }

    @Test
    void atLeastOneEntryIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 1L, ShowCondition.ALWAYS, null));
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 1L, ShowCondition.ALWAYS, List.of()));
    }

    @Test
    void entriesRequireOneToTwoLines() {
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc.MotdEntry(null));
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc.MotdEntry(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc.MotdEntry(List.of("one", "two", "three")));
    }

    @Test
    void nullLinesNormalizeToEmpty() {
        MotdDoc.MotdEntry entry = new MotdDoc.MotdEntry(Arrays.asList("top", null));

        assertEquals(List.of("top", ""), entry.lines());
    }

    private record TestScope(Map<String, Object> variables) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return variables.get(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }
}
