package art.arcane.gloss.motd;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("&dLine one\n&7Line two", doc.entries().get(1).joined());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        MotdDoc original = new MotdDoc(1, 4L, List.of(
            new MotdDoc.MotdEntry(List.of("&aHello")),
            new MotdDoc.MotdEntry(List.of("&aHello", "&7World"))));

        MotdDoc decoded = MotdDoc.parse("motd.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
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

        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 0L, entries));
        assertThrows(IllegalArgumentException.class,
            () -> new MotdDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, entries));
    }

    @Test
    void atLeastOneEntryIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new MotdDoc(1, 1L, List.of()));
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
}
