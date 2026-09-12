package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.GiveActionData;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.icon.IconItems;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Hands the clicking player items. A full inventory drops them at their feet by default; an author
 * who would rather the trade not happen at all sets {@code dropIfFull: false} and the list stops.
 */
public final class GiveMenuAction extends MenuAction<GiveActionData> {

  public GiveMenuAction(GiveActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Player viewer = context.player();
    ItemStack stack = IconItems.resolve(data.item(), viewer);
    if (viewer == null || stack == null) {
      return ActionOutcome.CONTINUE;
    }
    stack.setAmount(ShopAmounts.resolve(data.amount(), context.conditionScope()));
    Map<Integer, ItemStack> leftovers = viewer.getInventory().addItem(stack);
    if (leftovers.isEmpty()) {
      return ActionOutcome.CONTINUE;
    }
    if (!data.dropIfFullOrDefault()) {
      return ActionOutcome.STOP;
    }
    for (ItemStack leftover : leftovers.values()) {
      viewer.getWorld().dropItemNaturally(viewer.getLocation(), leftover);
    }
    send(viewer, GlossMessages.FORMS_GIVE_DROPPED);
    return ActionOutcome.CONTINUE;
  }

  static void send(Player viewer, TextKey key) {
    Gloss plugin = Gloss.instance;
    if (plugin != null && plugin.getLocalization() != null) {
      plugin.getLocalization().send(viewer, key);
    }
  }

  /** An authored deny message, rendered for the viewer and delivered through the component path. */
  static void deliver(Player viewer, String raw) {
    ComponentMessenger.send(viewer, ComponentText.component(
        TextUtils.parse(TextPipeline.menuText(viewer, raw))));
  }
}
