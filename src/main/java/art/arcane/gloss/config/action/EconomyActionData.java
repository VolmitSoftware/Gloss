package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.EconomyMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.List;
import java.util.Locale;

/** Moves or checks money through Vault. A failed check or withdrawal stops the list. */
public record EconomyActionData(String op, String amount, String denyMessage, HoloClickTrigger trigger,
                                String when, Integer cooldownTicks) implements MenuActionData {
  public static final String WITHDRAW = "withdraw";
  public static final String DEPOSIT = "deposit";
  public static final String HAS = "has";
  public static final List<String> OPERATIONS = List.of(WITHDRAW, DEPOSIT, HAS);

  public EconomyActionData {
    op = op == null ? WITHDRAW : op.trim().toLowerCase(Locale.ROOT);
    if (!OPERATIONS.contains(op)) {
      throw new IllegalArgumentException("economy op must be one of " + OPERATIONS + ": " + op);
    }
    amount = amount == null || amount.isBlank() ? "0" : amount.trim();
    denyMessage = denyMessage == null || denyMessage.isBlank() ? null : denyMessage;
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.ECONOMY;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new EconomyMenuAction(this);
  }
}
