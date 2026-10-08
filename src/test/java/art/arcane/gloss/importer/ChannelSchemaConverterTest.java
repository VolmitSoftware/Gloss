package art.arcane.gloss.importer;

import art.arcane.gloss.chat.ChannelDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelSchemaConverterTest {
    @Test
    void historicalUnfilteredChannelConvertsExactlyWithoutMutatingSource() {
        JsonObject source = source();
        ChannelSchemaConverter.Conversion converted = ChannelSchemaConverter.convert("global", source);
        assertTrue(converted.warnings().isEmpty());
        assertEquals(1, source.get("schemaVersion").getAsInt());
        assertEquals(2, converted.document().get("schemaVersion").getAsInt());
        assertEquals("re2", ChannelDoc.parse("global.json", converted.document().toString()).filtering().syntax());
    }

    @Test
    void literalPatternPreservesReplacementButReportsNewLimits() {
        JsonObject source = filtered("bad");
        source.getAsJsonArray("filters").get(0).getAsJsonObject().addProperty("replace", "$1\\path");
        ChannelSchemaConverter.Conversion converted = ChannelSchemaConverter.convert("global", source);
        assertEquals(1, converted.warnings().size());
        assertTrue(converted.warnings().getFirst().startsWith("Approximate:"));
        assertEquals("$1\\path", converted.document().getAsJsonArray("filters").get(0).getAsJsonObject().get("replace").getAsString());
    }

    @Test
    void supportedHistoricalRegexRequiresExplicitSemanticReview() {
        for (String pattern : List.of("(?i)\\bbadword\\b", "[0-9]+", "(?U)word", "cat|dog", "\\p{L}+")) {
            ChannelSchemaConverter.Conversion converted = ChannelSchemaConverter.convert("global", filtered(pattern));
            assertEquals(2, converted.warnings().size(), pattern);
            assertTrue(converted.warnings().getFirst().contains("Unicode"));
        }
    }

    @Test
    void unsupportedJavaConstructsBlockConversionIncludingVariants() {
        for (String pattern : List.of("(?<=a)b", "a(?!b)", "(a)\\1", "a++", "(?>a)", "(?x)a")) {
            assertThrows(VersionedGlossConverter.UnsupportedFormatException.class,
                () -> ChannelSchemaConverter.convert("global", filtered(pattern)), pattern);
        }
        JsonObject source = source();
        JsonObject variant = filtered("(?=a)");
        variant.addProperty("id", "special");
        variant.addProperty("when", "true");
        JsonArray variants = new JsonArray();
        variants.add(variant);
        source.add("variants", variants);
        assertThrows(VersionedGlossConverter.UnsupportedFormatException.class,
            () -> ChannelSchemaConverter.convert("global", source));
    }

    @Test
    void canonicalDocumentDoesNotRepeatMigrationWarnings() {
        JsonObject canonical = ChannelSchemaConverter.convert("global", filtered("bad")).document();
        assertFalse(canonical.getAsJsonObject("filtering").entrySet().isEmpty());
        assertTrue(ChannelSchemaConverter.convert("global", canonical).warnings().isEmpty());
        assertEquals(canonical, ChannelSchemaConverter.convert("global", canonical).document());
    }

    @Test
    void fractionalSchemaVersionIsRejected() {
        JsonObject source = source();
        source.addProperty("schemaVersion", 1.5);
        assertThrows(VersionedGlossConverter.UnsupportedFormatException.class,
            () -> ChannelSchemaConverter.convert("global", source));
    }

    private static JsonObject filtered(String pattern) {
        JsonObject source = source();
        JsonObject filter = new JsonObject();
        filter.addProperty("match", pattern);
        filter.addProperty("replace", "***");
        JsonArray filters = new JsonArray();
        filters.add(filter);
        source.add("filters", filters);
        return source;
    }

    private static JsonObject source() {
        return JsonParser.parseString("""
            {"schemaVersion":1,"revision":1,"channel":{"name":"global","default":true},
            "format":"{{ sender.name }}: {{ message }}","filters":[]}
            """).getAsJsonObject();
    }
}
