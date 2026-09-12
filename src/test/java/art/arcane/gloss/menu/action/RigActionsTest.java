package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.RigStateActionData;
import art.arcane.gloss.config.action.SetRigActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionType;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigActionsTest {
    private static final ActionContext NO_RIG = new ActionContext() {
        @Override
        public Player player() {
            return null;
        }

        @Override
        public String menuId() {
            return "menu";
        }

        @Override
        public String componentId() {
            return "button";
        }

        @Override
        public HoloClickTrigger trigger() {
            return HoloClickTrigger.RIGHT_CLICK;
        }

        @Override
        public NavigationResult navigate(NavigationRequest request) {
            return NavigationResult.NOT_FOUND;
        }
    };

    @Test
    void setRigValidatesVarAndValueExpressionAtLoad() {
        assertNull(new SetRigActionData("self", "opened", "true", null, null, null).invalidReason());
        assertNull(new SetRigActionData(null, "count", "rig.var.count + 1", null, null, null).invalidReason());
        assertNotNull(new SetRigActionData("self", null, "true", null, null, null).invalidReason());
        assertNotNull(new SetRigActionData("self", "opened", " ", null, null, null).invalidReason());
        assertNotNull(new SetRigActionData("self", "opened", "1 +", null, null, null).invalidReason());
        assertEquals("self", new SetRigActionData(null, "opened", "true", null, null, null).instanceOrSelf());
        assertEquals("altar", new SetRigActionData(" altar ", "opened", "true", null, null, null).instanceOrSelf());
        assertEquals(MenuActionType.SET_RIG, new SetRigActionData("self", "opened", "true", null, null, null).getType());
        assertEquals(20, new SetRigActionData("self", "opened", "true", null, "viewer.level > 1", 20).envelope().cooldownTicks());
    }

    @Test
    void rigStateValidatesItsState() {
        assertNull(new RigStateActionData("self", "open", null, null, null).invalidReason());
        assertNotNull(new RigStateActionData("self", "", null, null, null).invalidReason());
        assertEquals(MenuActionType.RIG_STATE, new RigStateActionData("self", "open", null, null, null).getType());
    }

    @Test
    void serializedNamesAndJsonRoundTrip() {
        assertEquals("setRig", MenuActionType.SET_RIG.getSerializedName());
        assertEquals("rigState", MenuActionType.RIG_STATE.getSerializedName());
        List<MenuActionData> actions = DocumentParsers.GSON.fromJson("""
            [ { "type": "setRig", "instance": "self", "var": "opened", "value": "true", "trigger": "right_click" },
              { "type": "rigState", "instance": "altar-1", "state": "open" } ]
            """, new TypeToken<List<MenuActionData>>() { }.getType());
        assertEquals(2, actions.size());
        SetRigActionData setRig = assertInstanceOf(SetRigActionData.class, actions.get(0));
        assertEquals(HoloClickTrigger.RIGHT_CLICK, setRig.trigger());
        RigStateActionData rigState = assertInstanceOf(RigStateActionData.class, actions.get(1));
        assertEquals("altar-1", rigState.instanceOrSelf());
        assertInstanceOf(SetRigMenuAction.class, setRig.createAction());
        assertInstanceOf(RigStateMenuAction.class, rigState.createAction());
    }

    @Test
    void bothActionsContinueTheListWhenNoRigIsInReach() {
        List<MenuAction<?>> actions = MenuAction.resolve(List.of(
            new SetRigActionData("self", "opened", "true", null, null, null),
            new RigStateActionData("self", "open", null, null, null)), "menu", "button");
        assertEquals(2, actions.size());
        assertEquals(ActionOutcome.CONTINUE, actions.get(0).execute(NO_RIG));
        assertEquals(ActionOutcome.CONTINUE, actions.get(1).execute(NO_RIG));
        assertTrue(actions.get(0) instanceof SetRigMenuAction);
    }
}
