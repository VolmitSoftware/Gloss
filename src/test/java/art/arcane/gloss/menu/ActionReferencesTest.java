package art.arcane.gloss.menu;

import art.arcane.gloss.config.action.IfActionData;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.menu.MenuDocument;
import art.arcane.gloss.config.menu.MenuDocumentParser;
import art.arcane.gloss.inventory.InventoryDoc;
import art.arcane.gloss.menu.action.ActionReferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActionReferencesTest {
    @Test
    void expandsNestedCallsWithIndependentGatesAndPreservesAuthoredMenu() {
        String source = """
            {"actions":{"greet":[{"type":"message","message":"Welcome"}],
              "welcome":[{"type":"call","action":"greet"},{"type":"delay","ticks":2}]},
             "components":[{"id":"button","data":{"type":"button","actions":[
              {"type":"call","action":"welcome","when":"true","trigger":"right_click","cooldownTicks":20}]}}]}
            """;
        MenuDocument document = MenuDocumentParser.parse("test", source);
        assertEquals(source, document.source());
        ButtonComponentData button = (ButtonComponentData) document.definition().getComponents().getFirst().data();
        IfActionData call = assertInstanceOf(IfActionData.class, button.actions().getFirst());
        assertEquals("true", call.when());
        assertEquals(20, call.cooldownTicks());
        assertEquals(2, call.then().size());
        assertInstanceOf(IfActionData.class, call.then().getFirst());
    }

    @Test
    void variantsToggleBranchesListTemplatesAndDialogCallbacksExpand() {
        String source = """
            {"schemaVersion":1,"revision":3,"resolution":"9x1","mask":["AAAAAAAAA"],
             "actions":{"save":[{"type":"message","message":"Saved"}]},
             "keys":{"A":{"type":"toggle","condition":"true","expectedValue":"true",
              "trueActions":[{"type":"call","action":"save"}],"falseActions":[{"type":"call","action":"save"}]}},
             "list":{"area":"A","var":"entry","source":"[1]","template":{"type":"button","actions":[{"type":"call","action":"save"}]}},
             "variants":[{"id":"alternate","when":"false","presentation":{"keys":{"A":{"type":"button","actions":[{"type":"dialog","buttons":[{"actions":[{"type":"call","action":"save"}]}]}]}}}}]}
            """;
        String resolved = ActionReferences.resolve(source);
        assertFalse(resolved.contains("\"type\":\"call\""));
        assertTrue(resolved.contains("\"type\":\"if\""));
        InventoryDoc doc = InventoryDoc.parse("test", source);
        assertEquals(3, doc.revision());
        ButtonComponentData template = (ButtonComponentData) doc.list().template();
        assertInstanceOf(IfActionData.class, template.actions().getFirst());
    }

    @Test
    void rejectsUnknownNamesCyclesIncludingUnusedDefinitionsAndUnboundedExpansion() {
        assertThrows(IllegalArgumentException.class, () -> ActionReferences.resolve("""
            {"actions":{"unused":[{"type":"call","action":"missing"}]}}
            """));
        assertThrows(IllegalArgumentException.class, () -> ActionReferences.resolve("""
            {"actions":{"a":[{"type":"call","action":"b"}],"b":[{"type":"call","action":"a"}]}}
            """));
        JsonObject root = new JsonObject();
        JsonObject library = new JsonObject();
        root.add("actions", library);
        library.add("a0", JsonParser.parseString("[{\"type\":\"message\",\"message\":\"leaf\"}]"));
        for (int index = 1; index <= 33; index++) {
            library.add("a" + index, JsonParser.parseString("[{\"type\":\"call\",\"action\":\"a" + (index - 1) + "\"}]"));
        }
        assertThrows(IllegalArgumentException.class, () -> ActionReferences.resolve(root.toString()));
        library = new JsonObject();
        root.add("actions", library);
        JsonArray repeated = new JsonArray();
        for (int index = 0; index < 40000; index++) {
            repeated.add(JsonParser.parseString("{\"type\":\"message\",\"message\":\"x\"}"));
        }
        library.add("big", repeated);
        assertThrows(IllegalArgumentException.class, () -> ActionReferences.resolve(root.toString()));
    }

    @Test
    void ordinaryDocumentsRetainExactBytesAndUnresolvedCallsCannotSilentlyRun() {
        String source = "{ \"components\" : [] }";
        assertEquals(source, ActionReferences.resolve(source));
        assertThrows(IllegalArgumentException.class, () -> MenuDocumentParser.parse("test", """
            {"components":[{"id":"x","data":{"type":"button","actions":[{"type":"call","action":"missing"}]}}]}
            """));
    }
}
