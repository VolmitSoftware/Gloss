package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.DelayMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

public record DelayActionData(Integer ticks, HoloClickTrigger trigger, String when,
                              Integer cooldownTicks) implements MenuActionData {
  public static final int MAX_TICKS = 20 * 60 * 60 * 24;

  @Override
  public MenuActionType getType() {
    return MenuActionType.DELAY;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new DelayMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return ticks == null || ticks < 1 || ticks > MAX_TICKS
        ? "declares a delay outside 1.." + MAX_TICKS + " ticks" : null;
  }
}
