package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;

/**
 * Nexo. Ids are the bare yml key with no namespace, matched case sensitively. {@code NexoItems} is
 * a Kotlin object, but every entry point used here has a real JVM static bridge.
 */
public final class NexoItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "nexo";
  }

  @Override
  public String pluginName() {
    return "Nexo";
  }

  @Override
  public ItemStack resolve(String itemId) {
    ItemBuilder builder = NexoItems.itemFromId(itemId);
    if (builder == null) {
      return null;
    }
    try {
      ItemStack stack = builder.build();
      return stack == null ? null : stack.clone();
    } catch (Exception malformedItem) {
      // Nexo builds lazily, so a malformed yml entry only blows up here and not at itemFromId
      return null;
    }
  }

  @Override
  public boolean has(String itemId) {
    return NexoItems.exists(itemId);
  }

  @Override
  public Collection<String> listIds() {
    return List.copyOf(NexoItems.itemNames());
  }
}
