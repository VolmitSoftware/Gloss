package art.arcane.gloss.hologram;

import art.arcane.volmlib.util.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramDescriptorTest {
    @Test
    void roundTripPreservesAllFields() {
        HologramDescriptor original = new HologramDescriptor(
            "spawn-info",
            "world",
            12.5D,
            64.0D,
            -7.25D,
            List.of("&dWelcome", "&7Line two", "&a%player_name%")
        );

        HologramDescriptor decoded = HologramDescriptor.fromJson(original.toJson());

        assertEquals(original, decoded);
    }

    @Test
    void roundTripSurvivesSerializedText() {
        HologramDescriptor original = new HologramDescriptor(
            "arena",
            "world_nether",
            0.0D,
            -32.5D,
            1000000.125D,
            List.of("plain", "", "&x&f&f&0&0&f&fhex")
        );

        String serialized = original.toJson().toString(4);
        HologramDescriptor decoded = HologramDescriptor.fromJson(new JSONObject(serialized));

        assertEquals(original, decoded);
    }

    @Test
    void fromJsonReadsLegacyShape() {
        JSONObject json = new JSONObject(
            "{\"id\":\"shop\",\"world\":\"world\",\"x\":1.5,\"y\":70.0,\"z\":-3.0,\"lines\":[\"&7Buy here\",\"&dOpen daily\"]}"
        );

        HologramDescriptor decoded = HologramDescriptor.fromJson(json);

        assertEquals("shop", decoded.id());
        assertEquals("world", decoded.world());
        assertEquals(1.5D, decoded.x());
        assertEquals(70.0D, decoded.y());
        assertEquals(-3.0D, decoded.z());
        assertEquals(List.of("&7Buy here", "&dOpen daily"), decoded.lines());
    }

    @Test
    void toJsonEmitsLegacyKeys() {
        HologramDescriptor descriptor = new HologramDescriptor("a", "w", 1.0D, 2.0D, 3.0D, List.of("x"));

        JSONObject json = descriptor.toJson();

        assertTrue(json.has("id"));
        assertTrue(json.has("world"));
        assertTrue(json.has("x"));
        assertTrue(json.has("y"));
        assertTrue(json.has("z"));
        assertTrue(json.has("lines"));
        assertEquals(1, json.getJSONArray("lines").length());
    }

    @Test
    void linesAreImmutableCopies() {
        HologramDescriptor descriptor = new HologramDescriptor("a", "w", 0.0D, 0.0D, 0.0D, List.of("one"));

        assertThrows(UnsupportedOperationException.class, () -> descriptor.lines().add("two"));
    }
}
