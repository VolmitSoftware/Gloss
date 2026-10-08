package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;

public record CallActionData(String action, HoloClickTrigger trigger, String when,
                             Integer cooldownTicks) implements MenuActionData {
    @Override
    public MenuActionType getType() {
        return MenuActionType.CALL;
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        throw new IllegalArgumentException("call requires a named action in the document actions object: " + action);
    }
}
