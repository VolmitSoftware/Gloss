package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SetRigMenuAction;

public record SetRigActionData(String instance, String var, String value, HoloClickTrigger trigger,
                               String when, Integer cooldownTicks) implements MenuActionData {
    public static final String SELF = "self";

    @Override
    public MenuActionType getType() {
        return MenuActionType.SET_RIG;
    }

    public String instanceOrSelf() {
        return instance == null || instance.isBlank() ? SELF : instance.trim();
    }

    public Expr parseValue() {
        return ExprParser.parse(value == null ? "" : value);
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new SetRigMenuAction(this);
    }

    @Override
    public String invalidReason() {
        if (var == null || var.isBlank()) {
            return "declares setRig without a var";
        }
        if (value == null || value.isBlank()) {
            return "declares setRig without a value";
        }
        try {
            parseValue();
        } catch (RuntimeException failure) {
            return "declares setRig with an invalid value expression: " + failure.getMessage();
        }
        return null;
    }
}
