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
            MotdDoc.MotdEntry.ofLines(List.of("&aHello")),
            MotdDoc.MotdEntry.ofLines(List.of("&aHello", "&7World"))), List.of());

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
        List<MotdDoc.MotdEntry> entries = List.of(MotdDoc.MotdEntry.ofLines(List.of("hi")));

        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 0L, ShowCondition.ALWAYS, entries, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, ShowCondition.ALWAYS, entries, List.of()));
    }

    @Test
    void atLeastOneEntryIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 1L, ShowCondition.ALWAYS, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 1L, ShowCondition.ALWAYS, List.of(), List.of()));
    }

    @Test
    void entriesRequireOneToTwoLines() {
        assertThrows(IllegalArgumentException.class, () -> MotdDoc.MotdEntry.ofLines(null));
        assertThrows(IllegalArgumentException.class, () -> MotdDoc.MotdEntry.ofLines(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> MotdDoc.MotdEntry.ofLines(List.of("one", "two", "three")));
    }

    @Test
    void nullLinesNormalizeToEmpty() {
        MotdDoc.MotdEntry entry = MotdDoc.MotdEntry.ofLines(Arrays.asList("top", null));

        assertEquals(List.of("top", ""), entry.lines());
    }

    @Test
    void anEntryCarriesItsFaviconSampleCountsAndVersion() {
        MotdDoc doc = MotdDoc.parse("motd.json", """
            { "schemaVersion": 1, "revision": 1,
              "entries": [ { "lines": ["&6&lMy Server"], "favicon": "icons/season4.png",
                             "sample": ["&e{{ server.online }} online", "&b3 staff"],
                             "online": "{{ server.online }}", "max": "{{ server.maxPlayers }}",
                             "version": "" } ] }
            """);
        MotdDoc.MotdEntry entry = doc.entries().get(0);

        assertEquals("icons/season4.png", entry.favicon());
        assertEquals(List.of("&e{{ server.online }} online", "&b3 staff"), entry.sample());
        assertEquals("{{ server.online }}", entry.online());
        assertEquals("{{ server.maxPlayers }}", entry.max());
        assertEquals("", entry.version());
    }

    @Test
    void theOptionalEntryFieldsDefaultToAbsent() {
        MotdDoc.MotdEntry entry = MotdDoc.MotdEntry.ofLines(List.of("hi"));

        assertEquals(null, entry.favicon());
        assertEquals(List.of(), entry.sample());
        assertEquals(null, entry.online());
        assertEquals(null, entry.max());
        assertEquals(null, entry.version());
    }

    @Test
    void serverLinksParseWithTheirTypeLabelAndUrl() {
        MotdDoc doc = MotdDoc.parse("motd.json", """
            { "schemaVersion": 1, "revision": 1,
              "entries": [ { "lines": ["hi"] } ],
              "links": [ { "type": "website", "url": "https://example.org" },
                         { "type": "community", "label": "Discord", "url": "https://discord.gg/example" } ] }
            """);

        assertEquals(2, doc.links().size());
        assertEquals("website", doc.links().get(0).type());
        assertEquals(null, doc.links().get(0).label());
        assertEquals("Discord", doc.links().get(1).label());
        assertEquals("https://discord.gg/example", doc.links().get(1).url());
    }

    @Test
    void aLinkWithAnUnknownTypeOrABadUrlIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc.MotdLink("teleport", null, "https://example.org"));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc.MotdLink("website", null, "javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc.MotdLink("website", null, "not a url"));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc.MotdLink(null, "  ", "https://example.org"));
    }

    @Test
    void aLabelledLinkNeedsNoKnownType() {
        MotdDoc.MotdLink link = new MotdDoc.MotdLink(null, "Store", "https://example.org/store");

        assertEquals(null, link.type());
        assertEquals("Store", link.label());
        assertTrue(link.isLabelled());
    }

    @Test
    void linksDefaultToNoneAndRoundTrip() {
        MotdDoc doc = new MotdDoc(1, 1L, ShowCondition.ALWAYS,
            List.of(MotdDoc.MotdEntry.ofLines(List.of("hi"))), null);

        assertEquals(List.of(), doc.links());
        assertEquals(doc, MotdDoc.parse("motd.json", BukkitJson.GSON.toJson(doc)));
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
