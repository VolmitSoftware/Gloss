package art.arcane.gloss.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class GlossVelocityDescriptorTest {
    @Test
    void descriptorDoesNotRequireAPacketEventsPlugin() throws IOException {
        try (InputStream stream = GlossVelocity.class.getResourceAsStream("/velocity-plugin.json")) {
            assertNotNull(stream);
            JsonObject descriptor = JsonParser.parseReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("art.arcane.gloss.velocity.GlossVelocity", descriptor.get("main").getAsString());
            if (!descriptor.has("dependencies")) {
                return;
            }
            JsonArray dependencies = descriptor.getAsJsonArray("dependencies");
            for (JsonElement element : dependencies) {
                String id = element.isJsonObject()
                        ? element.getAsJsonObject().get("id").getAsString()
                        : element.getAsString();
                assertFalse(id.equals("packetevents"), "Velocity Gloss must not depend on a PacketEvents plugin");
            }
        }
    }
}
