package art.arcane.gloss.importer;

import art.arcane.gloss.tab.TablistDoc;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistSchemaConverterTest {
    @Test
    void currentDocumentsRetainAuthoredShapeAndDefensiveCopies() {
        JsonObject source = json("""
            {"schemaVersion":3,"revision":19,"custom":"preserved","layout":{"enabled":true,"entries":21}}
            """);
        TablistSchemaConverter.Conversion conversion = TablistSchemaConverter.convert("tablist", source);
        assertEquals(source, conversion.document());
        conversion.document().addProperty("revision", 20);
        assertEquals(19, conversion.document().get("revision").getAsInt());
        source.addProperty("revision", 21);
        assertEquals(19, conversion.document().get("revision").getAsInt());
        assertTrue(conversion.warnings().isEmpty());
    }

    @Test
    void geometryPaddingPreservesAuthoredCoordinatesAndReportsApproximation() {
        JsonObject source = json("""
            {"schemaVersion":2,"revision":5,"layout":{"enabled":true,"columns":3,"rows":5,
             "slots":[{"column":2,"row":4,"text":"retained"}],
             "players":{"column":0,"columns":1,"rows":5,"filter":"true","overflow":"count"}}}
            """);
        TablistSchemaConverter.Conversion conversion = TablistSchemaConverter.convert("tablist", source);
        TablistDoc.Layout layout = TablistDoc.parse("tablist.json", conversion.document().toString()).layout();
        assertEquals(42, layout.entries());
        assertEquals(3, layout.presentation().columns());
        assertEquals(14, layout.presentation().rows());
        assertEquals(2, layout.slots().getFirst().column());
        assertEquals(4, layout.slots().getFirst().row());
        assertEquals(0, layout.sections().getFirst().row());
        assertEquals(5, layout.sections().getFirst().rows());
        assertFalse(conversion.warnings().isEmpty());
        assertEquals(2, source.get("schemaVersion").getAsInt());
    }

    @Test
    void invisibleSlotsOverwrittenByOldPlayerBlockAreRemovedExactly() {
        TablistSchemaConverter.Conversion conversion = TablistSchemaConverter.convert("tablist", json("""
            {"schemaVersion":2,"revision":1,"layout":{"enabled":true,"columns":1,"rows":20,
             "slots":[{"column":0,"row":0,"text":"never shown"}],
             "players":{"column":0,"columns":1,"rows":20}}}
            """));
        assertTrue(conversion.document().getAsJsonObject("layout").getAsJsonArray("slots").isEmpty());
        assertTrue(conversion.warnings().isEmpty());
    }

    @Test
    void fractionalAndUnknownVersionsAreUnsupported() {
        for (String version : new String[]{"2.1", "3.9", "99", "\"3\"", "null"}) {
            assertThrows(VersionedGlossConverter.UnsupportedFormatException.class,
                () -> TablistSchemaConverter.convert("tablist", json("{\"schemaVersion\":" + version + ",\"revision\":1}")));
        }
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }
}
