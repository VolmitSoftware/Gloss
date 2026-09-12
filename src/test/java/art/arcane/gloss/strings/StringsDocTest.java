package art.arcane.gloss.strings;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringsDocTest {
    @Test
    void parsesALocaleCatalogAndNormalisesTheLocaleId() {
        StringsDoc doc = StringsDoc.parse("de-de.json", """
            {"schemaVersion":1,"revision":3,"locale":"de-de","fallback":"en-us",
             "entries":{"shop.title":"Laden"}}
            """);

        assertEquals("de_DE", doc.locale());
        assertEquals("en_US", doc.fallback());
        assertEquals(3L, doc.revision());
        assertEquals("Laden", doc.entries().get("shop.title"));
    }

    @Test
    void anAbsentFallbackIsEmptyAndAbsentEntriesAreAnEmptyMap() {
        StringsDoc doc = StringsDoc.parse("en_US.json",
            "{\"schemaVersion\":1,\"revision\":1,\"locale\":\"en_US\"}");

        assertEquals("", doc.fallback());
        assertTrue(doc.entries().isEmpty());
    }

    @Test
    void aLocaleThatIsNotLanguageUnderscoreCountryIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> StringsDoc.parse("bad.json",
                "{\"schemaVersion\":1,\"revision\":1,\"locale\":\"deutsch\"}"));

        assertTrue(failure.getMessage().contains("locale"), failure.getMessage());
    }

    @Test
    void anEntryKeyOutsideTheAllowedShapeIsRefusedByName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> StringsDoc.parse("en_US.json",
                "{\"schemaVersion\":1,\"revision\":1,\"locale\":\"en_US\",\"entries\":{\"Shop Title\":\"x\"}}"));

        assertTrue(failure.getMessage().contains("Shop Title"), failure.getMessage());
    }

    @Test
    void anOverlongValueIsRefused() {
        String value = "x".repeat(StringsDoc.MAX_VALUE_LENGTH + 1);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new StringsDoc(1, 1L, "en_US", "", Map.of("shop.title", value)));

        assertTrue(failure.getMessage().contains("shop.title"), failure.getMessage());
    }

    @Test
    void anotherSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> StringsDoc.parse("en_US.json",
                "{\"schemaVersion\":2,\"revision\":1,\"locale\":\"en_US\"}"));
    }
}
