package art.arcane.gloss.dialog;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.ConfirmationDialog;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.DialogListDialog;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog;
import com.github.retrooper.packetevents.protocol.dialog.ServerLinksDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.Action;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.Input;
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The document-to-protocol translation, proved by encoding to NBT and decoding it back. Only the
 * round trip can show that the payload the client will hand back on a click carries the document
 * id, the button index and the token that authorizes the response.
 */
class DialogEncodingTest {

    @BeforeAll
    static void installPacketEventsApi() {
        DialogPacketEventsStub.install();
    }

    @AfterAll
    static void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    @Test
    void aNoticeRoundTripsItsTitleAndSingleButton() {
        Dialog decoded = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "&6Notice",
              "body": [ { "type": "text", "text": "Read this.", "width": 220 } ],
              "buttons": [ { "label": "&aOk", "width": 150 } ] }
            """));
        NoticeDialog notice = assertInstanceOf(NoticeDialog.class, decoded);
        assertEquals("Notice", plain(notice.getCommon().getTitle()));
        assertEquals("Ok", plain(notice.getAction().getButton().getLabel()));
        assertEquals(1, notice.getCommon().getBody().size());
        PlainMessageDialogBody body = assertInstanceOf(PlainMessageDialogBody.class,
            notice.getCommon().getBody().getFirst());
        assertEquals("Read this.", plain(body.getMessage().getContents()));
        assertEquals(220, body.getMessage().getWidth());
    }

    @Test
    void aButtonCarriesTheDocumentIdIndexAndToken() {
        Dialog decoded = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "multi_action", "title": "Trader",
              "buttons": [ { "label": "&aBuy" }, { "label": "&7Later" } ],
              "exit": { "label": "Close" } }
            """));
        MultiActionDialog multi = assertInstanceOf(MultiActionDialog.class, decoded);
        assertEquals(2, multi.getActions().size());
        assertNotNull(multi.getExitAction());
        assertPayload(multi.getActions().getFirst(), 0);
        assertPayload(multi.getActions().get(1), 1);
        assertPayload(multi.getExitAction(), 2);
    }

    @Test
    void aConfirmationRoundTripsYesAsZeroAndNoAsOne() {
        Dialog decoded = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "confirmation", "title": "Sure?",
              "yes": { "label": "&aYes" }, "no": { "label": "&cNo" } }
            """));
        ConfirmationDialog confirmation = assertInstanceOf(ConfirmationDialog.class, decoded);
        assertEquals("Yes", plain(confirmation.getYesButton().getButton().getLabel()));
        assertEquals("No", plain(confirmation.getNoButton().getButton().getLabel()));
        assertPayload(confirmation.getYesButton(), 0);
        assertPayload(confirmation.getNoButton(), 1);
    }

    @Test
    void everyInputControlRoundTripsUnderItsDeclaredKey() {
        Dialog decoded = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Inputs",
              "inputs": [
                { "type": "number", "key": "qty", "label": "Quantity", "start": 1, "end": 64, "step": 1, "initial": 4 },
                { "type": "option", "key": "color", "label": "Color",
                  "options": [ { "id": "red", "label": "Red" }, { "id": "blue", "label": "Blue", "initial": true } ] },
                { "type": "bool", "key": "gift", "label": "Gift", "initial": true },
                { "type": "text", "key": "note", "label": "Note", "maxLength": 40 }
              ],
              "buttons": [ { "label": "Ok" } ] }
            """));
        List<Input> inputs = ((NoticeDialog) decoded).getCommon().getInputs();
        assertEquals(List.of("qty", "color", "gift", "note"),
            inputs.stream().map(Input::getKey).toList());
        NumberRangeInputControl number = assertInstanceOf(NumberRangeInputControl.class, inputs.getFirst().getControl());
        assertEquals(1F, number.getRangeInfo().getStart());
        assertEquals(64F, number.getRangeInfo().getEnd());
        assertEquals(4F, number.getRangeInfo().getInitial());
        SingleOptionInputControl option = assertInstanceOf(SingleOptionInputControl.class, inputs.get(1).getControl());
        assertEquals(List.of("red", "blue"),
            option.getOptions().stream().map(SingleOptionInputControl.Entry::getId).toList());
        assertTrue(option.getOptions().get(1).isInitial());
        BooleanInputControl bool = assertInstanceOf(BooleanInputControl.class, inputs.get(2).getControl());
        assertTrue(bool.isInitial());
        TextInputControl text = assertInstanceOf(TextInputControl.class, inputs.get(3).getControl());
        assertEquals(40, text.getMaxLength());
    }

    @Test
    void serverLinksAndDialogListCarryTheirOwnGeometry() {
        Dialog links = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "server_links", "title": "Links",
              "columns": 1, "buttonWidth": 200, "exit": { "label": "Back" } }
            """));
        ServerLinksDialog linksDialog = assertInstanceOf(ServerLinksDialog.class, links);
        assertEquals(1, linksDialog.getColumns());
        assertEquals(200, linksDialog.getButtonWidth());

        Dialog list = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "dialog_list", "title": "All",
              "dialogs": [ "minecraft:quick_actions", "gloss:trader" ], "buttonWidth": 180 }
            """));
        DialogListDialog listDialog = assertInstanceOf(DialogListDialog.class, list);
        assertEquals(180, listDialog.getButtonWidth());
        assertNotNull(listDialog.getDialogs());
    }

    @Test
    void afterActionAndPauseSurviveTheRoundTrip() {
        Dialog decoded = roundTrip(rendered("""
            { "schemaVersion": 1, "revision": 1, "type": "notice", "title": "Wait",
              "afterAction": "wait_for_response", "canCloseWithEscape": false,
              "buttons": [ { "label": "Ok" } ] }
            """));
        CommonDialogData common = ((NoticeDialog) decoded).getCommon();
        assertEquals(DialogAction.WAIT_FOR_RESPONSE, common.getAfterAction());
        assertEquals(false, common.isCanCloseWithEscape());
    }

    private static void assertPayload(ActionButton button, int expectedIndex) {
        Action action = button.getAction();
        DynamicCustomAction custom = assertInstanceOf(DynamicCustomAction.class, action);
        assertEquals("gloss:dialog", custom.getId().toString());
        NBTCompound additions = custom.getAdditions();
        assertNotNull(additions);
        assertEquals("trader", additions.getStringTagValueOrThrow(DialogEncoder.PAYLOAD_DOCUMENT));
        assertEquals(expectedIndex, additions.getNumberTagValueOrThrow(DialogEncoder.PAYLOAD_BUTTON).intValue());
        assertEquals("token-1", additions.getStringTagValueOrThrow(DialogEncoder.PAYLOAD_TOKEN));
    }

    private static DialogRuntime.Rendered rendered(String raw) {
        DialogRuntime runtime = DialogRuntime.compile("trader", DialogDoc.parse("trader.json", raw));
        return runtime.render(null, Map.of());
    }

    private static Dialog roundTrip(DialogRuntime.Rendered rendered) {
        Dialog encoded = DialogEncoder.encode(rendered, "token-1");
        PacketWrapper<?> wrapper = DialogPacketEventsStub.wrapper();
        NBT nbt = Dialog.encodeDirect(encoded, wrapper);
        return Dialog.decodeDirect(nbt, wrapper, null);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component)
            .replaceAll("§[0-9a-fk-orx]", "");
    }
}
