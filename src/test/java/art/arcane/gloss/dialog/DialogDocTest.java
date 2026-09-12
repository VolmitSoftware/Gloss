package art.arcane.gloss.dialog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a dialog document accepts and what it refuses. Every refusal here is a shape the protocol
 * would either drop silently or throw on while a player is looking at the screen, so it is caught
 * at load instead.
 */
class DialogDocTest {

    @Test
    void noticeParsesWithOneButton() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "&6Notice",
              "body": [ { "type": "text", "text": "Read this." } ],
              "buttons": [ { "label": "&aOk" } ] }
            """);
        assertEquals(DialogType.NOTICE, doc.type());
        assertEquals(1, doc.buttons().size());
        assertEquals("&aOk", doc.buttons().getFirst().label());
        assertEquals("close", doc.afterAction());
    }

    @Test
    void noticeRefusesASecondButton() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice",
              "buttons": [ { "label": "a" }, { "label": "b" } ] }
            """));
        assertTrue(failure.getMessage().contains("notice"), failure.getMessage());
    }

    @Test
    void confirmationParsesWithYesAndNo() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "confirmation", "title": "Sure?",
              "yes": { "label": "&aYes", "actions": [ { "type": "message", "message": "yes" } ] },
              "no": { "label": "&cNo" } }
            """);
        assertEquals(DialogType.CONFIRMATION, doc.type());
        assertEquals("&aYes", doc.yes().label());
        assertEquals(1, doc.yes().actions().size());
        assertEquals("&cNo", doc.no().label());
    }

    @Test
    void confirmationWithoutYesAndNoIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "confirmation", "title": "Sure?" }
            """));
        assertTrue(failure.getMessage().contains("yes"), failure.getMessage());
    }

    @Test
    void multiActionParsesButtonsExitAndColumns() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Trader",
              "columns": 2,
              "buttons": [ { "label": "&aBuy", "tooltip": "buys", "width": 150 }, { "label": "&7Later" } ],
              "exit": { "label": "Close" } }
            """);
        assertEquals(DialogType.MULTI_ACTION, doc.type());
        assertEquals(2, doc.buttons().size());
        assertEquals(2, doc.columns());
        assertEquals("Close", doc.exit().label());
    }

    @Test
    void multiActionWithoutButtonsIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Trader" }
            """));
        assertTrue(failure.getMessage().contains("buttons"), failure.getMessage());
    }

    @Test
    void serverLinksParsesColumnsAndButtonWidth() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "server_links", "title": "Links",
              "columns": 1, "buttonWidth": 200, "exit": { "label": "Back" } }
            """);
        assertEquals(DialogType.SERVER_LINKS, doc.type());
        assertEquals(1, doc.columns());
        assertEquals(200, doc.buttonWidth());
    }

    @Test
    void dialogListParsesItsDialogIds() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "dialog_list", "title": "All",
              "dialogs": [ "trader", "bank" ] }
            """);
        assertEquals(DialogType.DIALOG_LIST, doc.type());
        assertEquals(List.of("trader", "bank"), doc.dialogs());
    }

    @Test
    void dialogListWithoutDialogsIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "dialog_list", "title": "All" }
            """));
        assertTrue(failure.getMessage().contains("dialogs"), failure.getMessage());
    }

    @Test
    void everyInputTypeParsesWithItsOwnParts() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Inputs",
              "inputs": [
                { "type": "number", "key": "qty", "label": "Quantity", "start": 1, "end": 64, "step": 1, "initial": 1 },
                { "type": "option", "key": "color", "label": "Color",
                  "options": [ { "id": "red", "label": "Red" }, { "id": "blue", "label": "Blue", "initial": true } ] },
                { "type": "bool", "key": "gift", "label": "Gift wrap", "initial": false },
                { "type": "text", "key": "note", "label": "Note", "maxLength": 32,
                  "multiline": { "maxLines": 2, "height": 40 } }
              ] }
            """);
        assertEquals(4, doc.inputs().size());
        DialogInput number = doc.inputs().getFirst();
        assertEquals(1F, number.start());
        assertEquals(64F, number.end());
        assertEquals(1F, number.initialNumber());
        DialogInput option = doc.inputs().get(1);
        assertEquals(2, option.options().size());
        assertTrue(option.options().get(1).initial());
        DialogInput bool = doc.inputs().get(2);
        assertEquals("false", bool.initial());
        DialogInput text = doc.inputs().get(3);
        assertEquals(32, text.maxLength());
        assertEquals(2, text.multiline().maxLines());
    }

    @Test
    void duplicateInputKeysAreRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice",
              "inputs": [ { "type": "text", "key": "note", "label": "A" },
                          { "type": "text", "key": "note", "label": "B" } ] }
            """));
        assertTrue(failure.getMessage().contains("note"), failure.getMessage());
    }

    @Test
    void inputKeysMustBeLowerCaseIdentifiers() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice",
              "inputs": [ { "type": "text", "key": "Note-1", "label": "A" } ] }
            """));
        assertTrue(failure.getMessage().contains("Note-1"), failure.getMessage());
    }

    @Test
    void numberInputRequiresStartBelowEnd() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice",
              "inputs": [ { "type": "number", "key": "qty", "label": "Q", "start": 10, "end": 10 } ] }
            """));
        assertTrue(failure.getMessage().contains("start"), failure.getMessage());
    }

    @Test
    void widthsClampToTheProtocolRange() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "buttonWidth": 9000,
              "body": [ { "type": "text", "text": "wide", "width": 0 } ],
              "inputs": [ { "type": "text", "key": "note", "label": "N", "width": 100000 } ],
              "buttons": [ { "label": "a", "width": -4 } ] }
            """);
        assertEquals(1, doc.body().getFirst().width());
        assertEquals(1024, doc.inputs().getFirst().width());
        assertEquals(1, doc.buttons().getFirst().width());
        assertEquals(1024, doc.buttonWidth());
    }

    @Test
    void unknownBodyTypeIsRefusedWithTheFileName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> DialogDoc.parse("trader.json", """
                { "schemaVersion": 1, "revision": 1, "type": "notice",
                  "body": [ { "type": "hologram", "text": "nope" } ] }
                """));
        assertTrue(failure.getMessage().startsWith("trader.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("hologram"), failure.getMessage());
    }

    @Test
    void unknownInputTypeIsRefusedWithTheFileName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> DialogDoc.parse("trader.json", """
                { "schemaVersion": 1, "revision": 1, "type": "notice",
                  "inputs": [ { "type": "colorpicker", "key": "c", "label": "C" } ] }
                """));
        assertTrue(failure.getMessage().startsWith("trader.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("colorpicker"), failure.getMessage());
    }

    @Test
    void aPausingDialogMayNotLeaveTheGamePaused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "pause": true, "afterAction": "none" }
            """));
        assertTrue(failure.getMessage().contains("pause"), failure.getMessage());
    }

    @Test
    void anUnknownAfterActionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "afterAction": "reopen" }
            """));
    }

    @Test
    void fallbackAndVariantsRoundTrip() {
        DialogDoc doc = parse("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Base",
              "show": "false",
              "select": { "priority": 5, "when": "viewer.level > 3" },
              "fallback": { "inventory": "trader" },
              "variants": [ { "id": "vip", "priority": 10, "when": "viewer.level > 30",
                              "presentation": { "title": "VIP", "buttons": [ { "label": "hi" } ] } } ] }
            """);
        assertEquals("trader", doc.fallback().inventory());
        assertNull(doc.fallback().menu());
        assertEquals(5, doc.select().priority());
        assertEquals(1, doc.variants().size());
        assertEquals("vip", doc.variants().getFirst().id());
        assertEquals("VIP", doc.variants().getFirst().presentation().title());
        assertNotNull(doc.show());
    }

    @Test
    void anUnsupportedSchemaVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            { "schemaVersion": 2, "revision": 1, "type": "notice" }
            """));
    }

    private static DialogDoc parse(String raw) {
        return DialogDoc.parse("test.json", raw);
    }
}
