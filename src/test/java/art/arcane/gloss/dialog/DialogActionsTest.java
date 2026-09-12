package art.arcane.gloss.dialog;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.CloseActionData;
import art.arcane.gloss.config.action.ConfirmActionData;
import art.arcane.gloss.config.action.DialogActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.enums.NavigationMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;


import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three actions a dialog contributes, and the context a dialog button runs them in. */
class DialogActionsTest {

    @Test
    void theDialogActionParsesItsIdAndArguments() {
        MenuActionData data = action("""
            { "type": "dialog", "id": "trader", "args": { "item": "wool", "count": 3 } }
            """);
        DialogActionData dialog = assertInstanceOf(DialogActionData.class, data);
        assertEquals(MenuActionType.DIALOG, dialog.getType());
        assertEquals("trader", dialog.id());
        assertEquals("wool", dialog.args().get("item"));
        assertNull(dialog.invalidReason());
    }

    @Test
    void aDialogActionWithoutAnIdCanNeverDoAnything() {
        DialogActionData dialog = (DialogActionData) action("{ \"type\": \"dialog\" }");
        assertNotNull(dialog.invalidReason());
    }

    @Test
    void theConfirmActionBuildsAConfirmationDocument() {
        ConfirmActionData confirm = (ConfirmActionData) action("""
            { "type": "confirm", "title": "&cDelete?", "text": "This cannot be undone.",
              "yes": [ { "type": "message", "message": "gone" } ], "no": [] }
            """);
        DialogDoc doc = confirm.document();
        assertEquals(DialogType.CONFIRMATION, doc.type());
        assertEquals("&cDelete?", doc.title());
        assertEquals(1, doc.body().size());
        assertEquals("This cannot be undone.", doc.body().getFirst().text());
        assertEquals(1, doc.yes().actions().size());
        assertInstanceOf(MessageActionData.class, doc.yes().actions().getFirst());
        assertTrue(doc.no().actions().isEmpty());
    }

    @Test
    void theCloseActionClosesTheSurfaceItRunsIn() {
        CloseActionData close = (CloseActionData) action("{ \"type\": \"close\" }");
        RecordingContext context = new RecordingContext();
        assertEquals(art.arcane.gloss.menu.action.ActionOutcome.STOP,
            close.createAction().execute(context));
        assertTrue(context.closed);
    }

    @Test
    void aDialogButtonContextNamesItselfAndRefusesNavigation() {
        DialogActionContext context = new DialogActionContext(null, "trader", 2, Map.of());
        assertEquals("dialog:trader", context.menuId());
        assertEquals("button:2", context.componentId());
        assertEquals(HoloClickTrigger.LEFT_CLICK, context.trigger());
        assertEquals(NavigationResult.DENIED,
            context.navigate(new NavigationRequest(NavigationMode.BACK, null)));
    }

    @Test
    void anOldClientFallsBackToTheInventoryThenTheMenu() {
        DialogDoc.Fallback both = new DialogDoc.Fallback("shop", "trader");
        assertEquals(DialogFallback.Target.INVENTORY, DialogFallback.choose(both).target());
        assertEquals("shop", DialogFallback.choose(both).id());

        DialogDoc.Fallback menuOnly = new DialogDoc.Fallback(null, "trader");
        assertEquals(DialogFallback.Target.MENU, DialogFallback.choose(menuOnly).target());
        assertEquals("trader", DialogFallback.choose(menuOnly).id());

        assertEquals(DialogFallback.NONE, DialogFallback.choose(null));
        assertEquals(DialogFallback.NONE, DialogFallback.choose(new DialogDoc.Fallback(null, null)));
    }

    private static MenuActionData action(String raw) {
        return DocumentParsers.GSON.fromJson(raw, MenuActionData.class);
    }

    private static final class RecordingContext implements ActionContext {
        private boolean closed;

        @Override
        public Player player() {
            return null;
        }

        @Override
        public String menuId() {
            return "test";
        }

        @Override
        public String componentId() {
            return "component";
        }

        @Override
        public HoloClickTrigger trigger() {
            return HoloClickTrigger.LEFT_CLICK;
        }

        @Override
        public NavigationResult navigate(NavigationRequest request) {
            return NavigationResult.DENIED;
        }

        @Override
        public void closeSurface(Player player) {
            closed = true;
        }
    }

}
