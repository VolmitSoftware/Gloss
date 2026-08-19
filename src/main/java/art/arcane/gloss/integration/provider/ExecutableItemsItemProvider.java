package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import com.ssomar.score.api.executableitems.ExecutableItemsAPI;
import com.ssomar.score.api.executableitems.config.ExecutableItemsManagerInterface;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ExecutableItems. Ids are the bare config file name. The API classes ship inside SCore, which
 * ExecutableItems hard depends on, so the ExecutableItems presence check covers both.
 */
public final class ExecutableItemsItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "executableitems";
  }

  @Override
  public String pluginName() {
    return "ExecutableItems";
  }

  @Override
  public boolean isReady() {
    return ExecutableItemsAPI.getExecutableItemsManager() != null;
  }

  @Override
  public ItemStack resolve(String itemId) {
    ExecutableItemsManagerInterface manager = ExecutableItemsAPI.getExecutableItemsManager();
    if (manager == null) {
      return null;
    }
    ItemStack stack = manager.getExecutableItem(itemId)
        .map(item -> item.buildItem(1, Optional.empty()))
        .orElse(null);
    return stack == null ? null : stack.clone();
  }

  @Override
  public boolean has(String itemId) {
    ExecutableItemsManagerInterface manager = ExecutableItemsAPI.getExecutableItemsManager();
    return manager != null && manager.isValidID(itemId);
  }

  @Override
  public Collection<String> listIds() {
    ExecutableItemsManagerInterface manager = ExecutableItemsAPI.getExecutableItemsManager();
    return manager == null ? List.of() : List.copyOf(manager.getExecutableItemIdsList());
  }
}
