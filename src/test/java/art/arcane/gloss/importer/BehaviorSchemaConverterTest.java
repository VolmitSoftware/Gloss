package art.arcane.gloss.importer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BehaviorSchemaConverterTest {
    @Test
    public void supportedUpgradePreservesOriginalAndReportsChangedMatching() {
        JsonObject source = JsonParser.parseString("{\"schemaVersion\":1,\"revision\":7,\"on\":["
            + "{\"trigger\":\"chat\",\"pattern\":\"^hello\",\"do\":[]}],\"custom\":true}").getAsJsonObject();
        JsonObject original = source.deepCopy();
        VersionedGlossConverter.Conversion result = VersionedGlossConverter.convert("behaviors", "greeting", source);
        assertEquals(original, source);
        assertEquals(2, result.document().get("schemaVersion").getAsInt());
        assertEquals(7, result.document().get("revision").getAsInt());
        assertTrue(result.document().get("custom").getAsBoolean());
        assertEquals(source.get("on"), result.document().get("on"));
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    public void unsupportedPatternRemainsUnconverted() {
        JsonObject source = JsonParser.parseString("{\"schemaVersion\":1,\"revision\":1,\"on\":["
            + "{\"trigger\":\"chat\",\"pattern\":\"(?=a)a\",\"do\":[]}]}").getAsJsonObject();
        JsonObject original = source.deepCopy();
        assertThrows(VersionedGlossConverter.UnsupportedFormatException.class,
            () -> VersionedGlossConverter.convert("behaviors", "unsupported", source));
        assertEquals(original, source);
    }

    @Test
    public void nonChatDocumentUpgradesExactly() {
        JsonObject source = JsonParser.parseString("{\"schemaVersion\":1,\"revision\":1,\"on\":["
            + "{\"trigger\":\"join\",\"do\":[]}]}").getAsJsonObject();
        assertTrue(VersionedGlossConverter.convert("behaviors", "join", source).warnings().isEmpty());
    }
}
