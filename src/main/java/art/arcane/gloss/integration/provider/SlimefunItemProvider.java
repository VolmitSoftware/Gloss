package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * Slimefun. Ids are {@code UPPER_SNAKE_CASE} and are looked up by an exact map get, so they are
 * matched case sensitively.
 */
public final class SlimefunItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "slimefun";
  }

  @Override
  public String pluginName() {
    return "Slimefun";
  }

  @Override
  public ItemStack resolve(String itemId) {
    SlimefunItem item = SlimefunItem.getById(itemId);
    if (item == null) {
      return null;
    }
    ItemStack stack = item.getItem();
    // getItem hands back the live SlimefunItemStack the registry itself holds
    return stack == null ? null : stack.clone();
  }

  @Override
  public boolean has(String itemId) {
    return SlimefunItem.getById(itemId) != null;
  }

  @Override
  public Collection<String> listIds() {
    return Slimefun.getRegistry().getEnabledSlimefunItems().stream()
        .map(SlimefunItem::getId)
        .toList();
  }

  @Override
  public String displayName(String itemId) {
    SlimefunItem item = SlimefunItem.getById(itemId);
    if (item == null) {
      return itemId;
    }
    String name = item.getItemName();
    return name == null || name.isBlank() ? itemId : name;
  }
}
