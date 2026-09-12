package art.arcane.gloss.config;

import art.arcane.gloss.behavior.BehaviorDoc;
import art.arcane.gloss.behavior.BehaviorTrigger;
import art.arcane.gloss.enums.MenuActionType;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The operator-facing schema is a contract: every action type and trigger the runtime accepts is documented. */
class BehaviorSchemaContractTest {
    private static final Set<String> CONTROL_FLOW = Set.of("delay", "sequence", "parallel", "repeat", "if", "switch",
        "chance", "cooldown", "emit", "broadcast", "effect", "particle", "stop", "setState", "addState", "clearState");

    @Test
    void theActionSchemaCoversEveryControlFlowActionType() throws IOException {
        JsonObject schema = read("schema/gloss.schema.json");
        JsonObject definitions = schema.getAsJsonObject("$defs");
        JsonObject action = definitions.getAsJsonObject("action");
        List<String> types = strings(action.getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum"));
        List<String> branches = new ArrayList<>();
        for (JsonElement branch : action.getAsJsonArray("allOf")) {
            branches.add(branch.getAsJsonObject().getAsJsonObject("if").getAsJsonObject("properties")
                .getAsJsonObject("type").get("const").getAsString());
        }

        for (String type : CONTROL_FLOW) {
            assertTrue(types.contains(type), "action type enum is missing " + type);
            assertTrue(branches.contains(type), "action allOf is missing a branch for " + type);
            assertNotNull(definitions.getAsJsonObject(type + "Action"), "$defs is missing " + type + "Action");
        }
        for (MenuActionType type : MenuActionType.values()) {
            assertTrue(types.contains(type.getSerializedName()),
                "action type enum is missing " + type.getSerializedName());
        }
    }

    @Test
    void nestedActionListsPointBackAtTheActionDefinition() throws IOException {
        JsonObject definitions = read("schema/gloss.schema.json").getAsJsonObject("$defs");
        JsonObject ifAction = definitions.getAsJsonObject("ifAction").getAsJsonObject("properties");
        JsonObject parallel = definitions.getAsJsonObject("parallelAction").getAsJsonObject("properties");

        assertEquals("#/$defs/actionList", ifAction.getAsJsonObject("then").get("$ref").getAsString());
        assertEquals("#/$defs/actionList", ifAction.getAsJsonObject("else").get("$ref").getAsString());
        assertEquals("#/$defs/actionList",
            parallel.getAsJsonObject("branches").getAsJsonObject("items").get("$ref").getAsString());
        assertEquals("#/$defs/action",
            definitions.getAsJsonObject("actionList").getAsJsonObject("items").get("$ref").getAsString());
    }

    @Test
    void theBehaviorSchemaDocumentsEveryTriggerAndTheEnvelope() throws IOException {
        JsonObject schema = read("schema/gloss-behaviors.schema.json");
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject entry = schema.getAsJsonObject("$defs").getAsJsonObject("entry");
        List<String> triggers = strings(entry.getAsJsonObject("properties").getAsJsonObject("trigger")
            .getAsJsonArray("enum"));

        assertEquals(BehaviorDoc.CURRENT_SCHEMA_VERSION,
            properties.getAsJsonObject("schemaVersion").get("const").getAsInt());
        assertEquals(List.of("schemaVersion", "revision"), strings(schema.getAsJsonArray("required")));
        assertTrue(properties.has("enabled"));
        assertTrue(properties.has("allowServerCommands"));
        assertTrue(properties.has("state"));
        assertTrue(properties.has("on"));
        for (BehaviorTrigger trigger : BehaviorTrigger.values()) {
            assertTrue(triggers.contains(trigger.key()), "behavior schema is missing trigger " + trigger.key());
        }
        assertEquals(BehaviorTrigger.values().length, triggers.size());
    }

    @Test
    void theBehaviorSchemaPinsTheStateDeclarationShape() throws IOException {
        JsonObject declaration = read("schema/gloss-behaviors.schema.json")
            .getAsJsonObject("$defs").getAsJsonObject("stateDeclaration");
        JsonObject properties = declaration.getAsJsonObject("properties");

        assertEquals(List.of("scope", "type"), strings(declaration.getAsJsonArray("required")));
        assertEquals(List.of("player", "world", "global"), strings(properties.getAsJsonObject("scope")
            .getAsJsonArray("enum")));
        assertEquals(List.of("number", "string", "boolean"), strings(properties.getAsJsonObject("type")
            .getAsJsonArray("enum")));
        assertTrue(properties.has("default"));
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static JsonObject read(String path) throws IOException {
        Path file = Path.of(System.getProperty("user.dir"), path);
        assertTrue(Files.isRegularFile(file), "missing schema " + file);
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
}
