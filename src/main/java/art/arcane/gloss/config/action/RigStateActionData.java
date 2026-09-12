package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.RigStateMenuAction;

public record RigStateActionData(String instance, String state, HoloClickTrigger trigger,
                                 String when, Integer cooldownTicks) implements MenuActionData {
    @Override
    public MenuActionType getType() {
        return MenuActionType.RIG_STATE;
    }

    public String instanceOrSelf() {
        return instance == null || instance.isBlank() ? SetRigActionData.SELF : instance.trim();
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new RigStateMenuAction(this);
    }

    @Override
    public String invalidReason() {
        return state == null || state.isBlank() ? "declares rigState without a state" : null;
    }
}
