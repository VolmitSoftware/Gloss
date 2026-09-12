package art.arcane.gloss.bedrock;

import art.arcane.gloss.dialog.DialogDoc;
import art.arcane.gloss.dialog.DialogRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a dialog becomes a Bedrock form. Geyser has two shapes and only two: a simple form is a
 * column of buttons, a custom form is a column of controls with one submit. A dialog with inputs
 * has to be the second, because a simple form has nowhere to type.
 */
class FormsMappingTest {

    @Test
    void aDialogWithNoInputsIsASimpleForm() {
        FormSpec spec = map("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "&6Trader",
              "body": [ { "type": "text", "text": "Pick a trade." } ],
              "buttons": [ { "label": "&aBuy" }, { "label": "&7Later" } ] }
            """);
        FormSpec.Simple simple = assertInstanceOf(FormSpec.Simple.class, spec);
        assertEquals("Trader", simple.title());
        assertEquals("Pick a trade.", simple.content());
        assertEquals(List.of("Buy", "Later"), simple.buttons());
    }

    @Test
    void aConfirmationIsASimpleFormWithTwoButtons() {
        FormSpec spec = map("""
            { "schemaVersion": 1, "revision": 1, "type": "confirmation", "title": "Sure?",
              "yes": { "label": "&aYes" }, "no": { "label": "&cNo" } }
            """);
        FormSpec.Simple simple = assertInstanceOf(FormSpec.Simple.class, spec);
        assertEquals(List.of("Yes", "No"), simple.buttons());
    }

    @Test
    void aDialogWithInputsIsACustomFormWithOneControlPerInput() {
        FormSpec spec = map("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Inputs",
              "inputs": [
                { "type": "text", "key": "note", "label": "Note" },
                { "type": "bool", "key": "gift", "label": "Gift", "initial": true },
                { "type": "number", "key": "qty", "label": "Quantity", "start": 1, "end": 64, "step": 1 },
                { "type": "option", "key": "color", "label": "Color",
                  "options": [ { "id": "red", "label": "Red" }, { "id": "blue", "label": "Blue", "initial": true } ] }
              ],
              "buttons": [ { "label": "Buy" } ] }
            """);
        FormSpec.Custom custom = assertInstanceOf(FormSpec.Custom.class, spec);
        assertEquals(4, custom.controls().size());

        FormSpec.Input text = assertInstanceOf(FormSpec.Input.class, custom.controls().get(0));
        assertEquals("note", text.key());
        assertEquals("Note", text.label());

        FormSpec.Toggle toggle = assertInstanceOf(FormSpec.Toggle.class, custom.controls().get(1));
        assertEquals("gift", toggle.key());
        assertTrue(toggle.initial());

        FormSpec.Slider slider = assertInstanceOf(FormSpec.Slider.class, custom.controls().get(2));
        assertEquals(1F, slider.min());
        assertEquals(64F, slider.max());
        assertEquals(1F, slider.step());

        FormSpec.Dropdown dropdown = assertInstanceOf(FormSpec.Dropdown.class, custom.controls().get(3));
        assertEquals(List.of("Red", "Blue"), dropdown.options());
        assertEquals(1, dropdown.initialIndex());
    }

    @Test
    void theButtonIndexesSurviveTheMapping() {
        FormSpec spec = map("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Trader",
              "buttons": [ { "label": "Buy" }, { "label": "Later" } ],
              "exit": { "label": "Close" } }
            """);
        FormSpec.Simple simple = assertInstanceOf(FormSpec.Simple.class, spec);
        assertEquals(List.of(0, 1, 2), simple.buttonIndexes(),
            "a Bedrock button press has to come back as the same click index the packet path uses");
    }

    @Test
    void itemBodiesAreDroppedRatherThanDrawnAsText() {
        FormSpec spec = map("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Notice",
              "body": [ { "type": "text", "text": "Read this." },
                        { "type": "item", "item": { "type": "item", "item": "minecraft:emerald" },
                          "description": "&aEmeralds accepted" } ],
              "buttons": [ { "label": "Ok" } ] }
            """);
        FormSpec.Simple simple = assertInstanceOf(FormSpec.Simple.class, spec);
        assertEquals("Read this.\nEmeralds accepted", simple.content());
    }

    private static FormSpec map(String raw) {
        DialogRuntime runtime = DialogRuntime.compile("trader", DialogDoc.parse("trader.json", raw));
        return FormsMapping.map(runtime.render(null, Map.of()));
    }
}
