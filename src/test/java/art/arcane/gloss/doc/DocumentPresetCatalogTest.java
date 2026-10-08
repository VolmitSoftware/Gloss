package art.arcane.gloss.doc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentPresetCatalogTest {
    @Test
    void appliesDefaultsParentsPresetAndDocumentInOrderWithoutChangingIdentity() {
        DocumentPresetCatalog catalog = catalog("""
            "defaults":{"boards":{"enabled":true,"default":{"title":"Global","lines":["Global"],"layout":{"refresh":{"titleTicks":20,"textTicks":40}}}}},
            "presets":{"boards":{
              "base":{"values":{"default":{"title":"Base","layout":{"refresh":{"textTicks":10}}}}},
              "compact":{"extends":"base","values":{"default":{"lines":["Preset"],"layout":{"refresh":{"valueTicks":5}}}}}
            }}
            """);
        String source = """
            {"schemaVersion":2,"revision":17,"preset":"compact","default":{"title":"Document"}}
            """;
        JsonObject resolved = object(catalog.resolve("boards", source));
        assertEquals(2, resolved.get("schemaVersion").getAsInt());
        assertEquals(17, resolved.get("revision").getAsLong());
        assertFalse(resolved.has("preset"));
        assertEquals("Document", resolved.getAsJsonObject("default").get("title").getAsString());
        assertEquals("Preset", resolved.getAsJsonObject("default").getAsJsonArray("lines").get(0).getAsString());
        JsonObject refresh = resolved.getAsJsonObject("default").getAsJsonObject("layout").getAsJsonObject("refresh");
        assertEquals(20, refresh.get("titleTicks").getAsInt());
        assertEquals(10, refresh.get("textTicks").getAsInt());
        assertEquals(5, refresh.get("valueTicks").getAsInt());
        assertEquals("compact", object(source).get("preset").getAsString());
    }

    @Test
    void arraysReplaceAndExplicitNullResetsInheritedValues() {
        DocumentPresetCatalog catalog = catalog("""
            "defaults":{"boards":{"default":{"lines":["A","B"],"title":"Inherited"}}}
            """);
        JsonObject resolved = object(catalog.resolve("boards", """
            {"default":{"lines":[],"title":null}}
            """));
        assertEquals(0, resolved.getAsJsonObject("default").getAsJsonArray("lines").size());
        assertEquals(true, resolved.getAsJsonObject("default").get("title").isJsonNull());
    }

    @Test
    void resolutionsCannotMutateSharedPresets() {
        DocumentPresetCatalog catalog = catalog("""
            "presets":{"boards":{"base":{"values":{"default":{"title":"Original"}}}}}
            """);
        catalog.resolve("boards", "{\"preset\":\"base\",\"default\":{\"title\":\"Changed\"}}");
        JsonObject second = object(catalog.resolve("boards", "{\"preset\":\"base\"}"));
        assertEquals("Original", second.getAsJsonObject("default").get("title").getAsString());
    }

    @Test
    void refusesMissingCyclesUnknownKindsAndIdentityOverrides() {
        assertThrows(IllegalArgumentException.class, () -> DocumentPresetCatalog.empty().resolve("boards", "{\"preset\":\"missing\"}"));
        assertThrows(IllegalArgumentException.class, () -> catalog("""
            "presets":{"boards":{"a":{"extends":"b"},"b":{"extends":"a"}}}
            """));
        assertThrows(IllegalArgumentException.class, () -> catalog("""
            "presets":{"boards":{"a":{"extends":"missing"}}}
            """));
        assertThrows(IllegalArgumentException.class, () -> catalog("\"defaults\":{\"boardz\":{}}"));
        for (String field : new String[]{"schemaVersion", "revision", "id", "preset"}) {
            assertThrows(IllegalArgumentException.class, () -> catalog(
                "\"defaults\":{\"boards\":{\"" + field + "\":1}}"));
        }
    }

    @Test
    void preservesUninheritedSourceExactly() {
        String source = "{ \"schemaVersion\": 2, \"revision\": 1 }\n";
        assertEquals(source, DocumentPresetCatalog.empty().resolve("boards", source));
    }

    @Test
    void validatesExactEnvelopeNumbersAndFields() {
        assertThrows(ArithmeticException.class, () -> DocumentPresetCatalog.parse("presets.json", """
            {"schemaVersion":1.5,"revision":1}
            """));
        assertThrows(IllegalArgumentException.class, () -> catalog("\"default\":{}"));
        assertThrows(IllegalArgumentException.class, () -> catalog("""
            "presets":{"boards":{"a":{"value":{}}}}
            """));
    }

    private static DocumentPresetCatalog catalog(String fields) {
        return DocumentPresetCatalog.parse("presets.json", "{\"schemaVersion\":1,\"revision\":1," + fields + "}");
    }

    @Test
    void rejectsOversizedSourceAndDeepPreparedValues() {
        assertThrows(IllegalArgumentException.class, () -> catalog("\"defaults\":{\"boards\":{\"title\":\""
            + "x".repeat(DocumentPresetCatalog.MAX_SOURCE_BYTES) + "\"}}"));
        String nested = "{\"child\":".repeat(130) + "true" + "}".repeat(130);
        assertThrows(IllegalArgumentException.class, () -> catalog("\"defaults\":{\"boards\":" + nested + "}"));
    }

    private static JsonObject object(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }
}
