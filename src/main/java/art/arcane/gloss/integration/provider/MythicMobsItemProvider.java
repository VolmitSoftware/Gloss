package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.items.ItemExecutor;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;

/**
 * MythicMobs items. Ids are the bare item config name with no namespace.
 */
public final class MythicMobsItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "mythicmobs";
  }

  @Override
  public String pluginName() {
    return "MythicMobs";
  }

  @Override
  public boolean isReady() {
    return items() != null;
  }

  @Override
  public ItemStack resolve(String itemId) {
    ItemExecutor items = items();
    if (items == null) {
      return null;
    }
    ItemStack stack = items.getItemStack(itemId);
    return stack == null ? null : stack.clone();
  }

  @Override
  public boolean has(String itemId) {
    ItemExecutor items = items();
    return items != null && items.getItem(itemId).isPresent();
  }

  @Override
  public Collection<String> listIds() {
    ItemExecutor items = items();
    return items == null ? List.of() : List.copyOf(items.getItemNames());
  }

  private static ItemExecutor items() {
    // inst() is null between class load and MythicMobs finishing its own enable
    MythicBukkit mythic = MythicBukkit.inst();
    return mythic == null ? null : mythic.getItemManager();
  }
}
