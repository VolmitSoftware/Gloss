package art.arcane.gloss.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatFilterConformanceTest {
    @Test
    void sharedBrowserAndServerFixturesAgree() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/chat/filter-conformance.json")) {
            assertNotNull(stream);
            for (JsonElement element : JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject fixture = element.getAsJsonObject();
                JsonObject document = new JsonObject();
                document.addProperty("schemaVersion", 2);
                document.addProperty("revision", 1);
                document.add("channel", JsonParser.parseString("{\"name\":\"global\"}"));
                document.addProperty("format", "{{ message }}");
                document.add("filters", fixture.get("filters"));
                if (fixture.has("filtering")) {
                    document.add("filtering", fixture.get("filtering"));
                }
                String name = fixture.get("name").getAsString();
                if (fixture.has("invalid")) {
                    assertThrows(IllegalArgumentException.class, () -> ChannelDoc.parse("global.json", document.toString()), name);
                    continue;
                }
                ChannelRuntime channel = ChannelRuntime.of("global", ChannelDoc.parse("global.json", document.toString()));
                String expected = fixture.get("expected").isJsonNull() ? null : fixture.get("expected").getAsString();
                assertEquals(expected, ChatFilters.apply(channel, fixture.get("input").getAsString(), () -> 0L), name);
            }
        }
    }
}
