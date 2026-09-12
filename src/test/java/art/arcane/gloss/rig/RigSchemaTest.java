package art.arcane.gloss.rig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigSchemaTest {
    @Test
    void actionSchemaDeclaresTheRigActions() throws IOException {
        JsonObject definitions = schema("gloss.schema.json").getAsJsonObject("$defs");
        List<String> types = strings(definitions.getAsJsonObject("action")
            .getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum"));

        assertTrue(types.contains("setRig"), "action enum omits setRig: " + types);
        assertTrue(types.contains("rigState"), "action enum omits rigState: " + types);
        assertEquals(List.of("var", "value"), required(definitions.getAsJsonObject("setRigAction")));
        assertEquals(List.of("state"), required(definitions.getAsJsonObject("rigStateAction")));
        assertTrue(definitions.getAsJsonObject("setRigAction").getAsJsonObject("properties").has("instance"));
        assertTrue(definitions.getAsJsonObject("rigStateAction").getAsJsonObject("properties").has("instance"));
    }

    @Test
    void actionSchemaBranchesOnTheRigActionTypes() throws IOException {
        JsonArray variants = schema("gloss.schema.json").getAsJsonObject("$defs")
            .getAsJsonObject("action").getAsJsonArray("allOf");
        List<String> branches = new ArrayList<>();
        for (JsonElement element : variants) {
            branches.add(element.getAsJsonObject().getAsJsonObject("if").getAsJsonObject("properties")
                .getAsJsonObject("type").get("const").getAsString());
        }

        assertTrue(branches.contains("setRig"), "action branches omit setRig: " + branches);
        assertTrue(branches.contains("rigState"), "action branches omit rigState: " + branches);
    }

    @Test
    void motionSchemaPinsTheDocumentEnvelopeAndChannels() throws IOException {
        JsonObject schema = schema("gloss-motion.schema.json");
        JsonObject properties = schema.getAsJsonObject("properties");

        assertEquals(1, properties.getAsJsonObject("schemaVersion").get("const").getAsInt());
        assertEquals(List.of("schemaVersion", "revision", "tracks"), required(schema));
        assertEquals(List.of("once", "loop", "pingpong"),
            strings(properties.getAsJsonObject("loop").getAsJsonArray("enum")));
        assertEquals(1, properties.getAsJsonObject("fps").get("minimum").getAsInt());
        assertEquals(120, properties.getAsJsonObject("fps").get("maximum").getAsInt());
        JsonObject track = schema.getAsJsonObject("$defs").getAsJsonObject("track");
        assertEquals(List.of("channel", "keyframes"), required(track));
        List<String> channels = strings(track.getAsJsonObject("properties")
            .getAsJsonObject("channel").getAsJsonArray("enum"));
        assertTrue(channels.containsAll(List.of("translation.x", "rotation.y", "scale.z", "glow", "visible",
            "opacity", "brightness")), "motion channel enum is incomplete: " + channels);
    }

    @Test
    void rigSchemaPinsBonesPartsAndTheGraph() throws IOException {
        JsonObject schema = schema("gloss-rigs.schema.json");
        JsonObject properties = schema.getAsJsonObject("properties");

        assertEquals(1, properties.getAsJsonObject("schemaVersion").get("const").getAsInt());
        assertEquals(List.of("schemaVersion", "revision", "bones", "parts"), required(schema));
        assertEquals(List.of("block", "item", "text"), strings(schema.getAsJsonObject("$defs")
            .getAsJsonObject("part").getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum")));
        assertEquals(List.of("id"), required(schema.getAsJsonObject("$defs").getAsJsonObject("bone")));
        assertTrue(properties.has("graph"));
        assertTrue(properties.has("hitboxes"));
        assertTrue(properties.has("lod"));
    }

    @Test
    void rigInstanceSchemaPinsThePlacement() throws IOException {
        JsonObject schema = schema("gloss-rig-instances.schema.json");
        JsonObject properties = schema.getAsJsonObject("properties");

        assertEquals(1, properties.getAsJsonObject("schemaVersion").get("const").getAsInt());
        assertEquals(List.of("schemaVersion", "revision", "rig", "world", "x", "y", "z"), required(schema));
        assertEquals(0.01D, properties.getAsJsonObject("scale").get("minimum").getAsDouble(), 0D);
        assertEquals(64D, properties.getAsJsonObject("scale").get("maximum").getAsDouble(), 0D);
    }

    @Test
    void hologramSchemaDeclaresRichLinesPagesActionsHitboxAndMotion() throws IOException {
        JsonObject schema = schema("gloss-holograms.schema.json");
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject definitions = schema.getAsJsonObject("$defs");

        assertEquals(3, properties.getAsJsonObject("schemaVersion").get("const").getAsInt());
        assertEquals("#/$defs/line", properties.getAsJsonObject("lines")
            .getAsJsonObject("items").get("$ref").getAsString());
        JsonArray lineForms = definitions.getAsJsonObject("line").getAsJsonArray("oneOf");
        assertEquals(2, lineForms.size());
        List<String> objectKeys = List.copyOf(lineForms.get(1).getAsJsonObject()
            .getAsJsonObject("properties").keySet());
        assertTrue(objectKeys.containsAll(List.of("text", "item", "head", "block", "entity", "show", "scale")),
            "hologram line object omits a content key: " + objectKeys);
        assertEquals(List.of("id", "lines"), required(definitions.getAsJsonObject("page")));
        assertTrue(properties.has("actions"));
        assertEquals(1.2D, definitions.getAsJsonObject("hitbox").getAsJsonObject("properties")
            .getAsJsonObject("width").get("default").getAsDouble(), 0D);
        assertEquals("string", properties.getAsJsonObject("motion").get("type").getAsString());
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static List<String> required(JsonObject object) {
        return object.has("required") ? strings(object.getAsJsonArray("required")) : List.of();
    }

    private static JsonObject schema(String name) throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("schema", name))).getAsJsonObject();
    }
}
