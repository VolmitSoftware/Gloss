package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * CraftEngine. Ids are {@code namespace:id}; a bare id is also accepted and resolved by a
 * cross-namespace path search, so both forms are passed through verbatim.
 */
public final class CraftEngineItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "craftengine";
  }

  @Override
  public String pluginName() {
    return "CraftEngine";
  }

  @Override
  public ItemStack resolve(String itemId) {
    BukkitItemDefinition definition = CraftEngineItems.byId(itemId);
    if (definition == null) {
      return null;
    }
    ItemStack stack = definition.buildBukkitItem();
    return stack == null ? null : stack.clone();
  }

  @Override
  public boolean has(String itemId) {
    return CraftEngineItems.byId(itemId) != null;
  }

  @Override
  public Collection<String> listIds() {
    return CraftEngineItems.loadedItems().keySet().stream()
        .map(Key::asString)
        .toList();
  }
}
