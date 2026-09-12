package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.TakeActionData;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.icon.IconItems;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Takes items from the clicking player, all or nothing. A partial take would leave a shop having
 * charged for something it did not deliver, so the count is checked before anything is removed.
 */
public final class TakeMenuAction extends MenuAction<TakeActionData> {

  public TakeMenuAction(TakeActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Player viewer = context.player();
    ItemStack stack = IconItems.resolve(data.item(), viewer);
    if (viewer == null || stack == null) {
      return ActionOutcome.STOP;
    }
    int amount = ShopAmounts.resolve(data.amount(), context.conditionScope());
    stack.setAmount(amount);
    if (!viewer.getInventory().containsAtLeast(stack, amount)) {
      deny(viewer);
      return ActionOutcome.STOP;
    }
    viewer.getInventory().removeItem(stack);
    return ActionOutcome.CONTINUE;
  }

  private void deny(Player viewer) {
    if (data.denyMessage() == null) {
      GiveMenuAction.send(viewer, GlossMessages.FORMS_TAKE_MISSING);
      return;
    }
    GiveMenuAction.deliver(viewer, data.denyMessage());
  }
}
