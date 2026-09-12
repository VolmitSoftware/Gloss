package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.EconomyActionData;
import art.arcane.gloss.integration.VaultEconomyHook;
import art.arcane.gloss.locale.GlossMessages;
import org.bukkit.entity.Player;

/**
 * Moves or checks money through Vault. With no economy plugin installed nothing is charged and
 * nothing is delivered: a shop that cannot take payment must not hand out goods.
 */
public final class EconomyMenuAction extends MenuAction<EconomyActionData> {

  private static final VaultEconomyHook ECONOMY = new VaultEconomyHook();

  public EconomyMenuAction(EconomyActionData data) {
    super(data);
  }

  /** A deposit is a payout: it never gates what follows it, even when the economy refuses it. */
  public static boolean continuesOnFailure(String op) {
    return EconomyActionData.DEPOSIT.equals(op);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Player viewer = context.player();
    if (viewer == null) {
      return ActionOutcome.STOP;
    }
    double amount = ShopAmounts.resolveMoney(data.amount(), context.conditionScope());
    if (!ECONOMY.available()) {
      if (continuesOnFailure(data.op())) {
        return ActionOutcome.CONTINUE;
      }
      GiveMenuAction.send(viewer, GlossMessages.FORMS_ECONOMY_UNAVAILABLE);
      return ActionOutcome.STOP;
    }
    boolean succeeded = switch (data.op()) {
      case EconomyActionData.DEPOSIT -> ECONOMY.deposit(viewer, amount);
      case EconomyActionData.HAS -> ECONOMY.has(viewer, amount);
      default -> ECONOMY.has(viewer, amount) && ECONOMY.withdraw(viewer, amount);
    };
    if (succeeded || continuesOnFailure(data.op())) {
      return ActionOutcome.CONTINUE;
    }
    deny(viewer);
    return ActionOutcome.STOP;
  }

  private void deny(Player viewer) {
    if (data.denyMessage() == null) {
      GiveMenuAction.send(viewer, GlossMessages.FORMS_ECONOMY_DENIED);
      return;
    }
    GiveMenuAction.deliver(viewer, data.denyMessage());
  }
}
