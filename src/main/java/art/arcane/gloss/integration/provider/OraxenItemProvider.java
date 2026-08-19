package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;

/**
 * Oraxen. Ids are the bare yml key with no namespace, matched case sensitively.
 */
public final class OraxenItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "oraxen";
  }

  @Override
  public String pluginName() {
    return "Oraxen";
  }

  @Override
  public ItemStack resolve(String itemId) {
    ItemBuilder builder = OraxenItems.getItemById(itemId);
    if (builder == null) {
      return null;
    }
    ItemStack stack = builder.build();
    return stack == null ? null : stack.clone();
  }

  @Override
  public boolean has(String itemId) {
    return OraxenItems.exists(itemId);
  }

  @Override
  public Collection<String> listIds() {
    return List.copyOf(OraxenItems.getNames());
  }
}
