package art.arcane.gloss.integration.provider;

import art.arcane.gloss.integration.ItemProvider;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MMOItems. Ids are {@code TYPE:ID}, both halves uppercase by convention and matched case
 * sensitively, so the authored id is passed through untouched.
 */
public final class MMOItemsItemProvider implements ItemProvider {

  @Override
  public String id() {
    return "mmoitems";
  }

  @Override
  public String pluginName() {
    return "MMOItems";
  }

  @Override
  public boolean isReady() {
    return MMOItems.plugin != null;
  }

  @Override
  public boolean requiresMainThread() {
    // MMOItems builds items through managers that are not safe off the main thread, which two
    // independent shipping plugins work around by forcing a sync call
    return true;
  }

  @Override
  public ItemStack resolve(String itemId) {
    String[] parts = itemId.split(":", 2);
    if (parts.length != 2) {
      return null;
    }
    ItemStack stack = MMOItems.plugin.getItem(parts[0], parts[1]);
    return stack == null ? null : stack.clone();
  }

  @Override
  public boolean has(String itemId) {
    String[] parts = itemId.split(":", 2);
    if (parts.length != 2) {
      return false;
    }
    Type type = Type.get(parts[0]);
    return type != null && MMOItems.plugin.getTemplates().getTemplate(type, parts[1]) != null;
  }

  @Override
  public Collection<String> listIds() {
    List<String> ids = new ArrayList<>();
    for (Type type : MMOItems.plugin.getTypes().getAll()) {
      for (String name : MMOItems.plugin.getTemplates().getTemplateNames(type)) {
        ids.add(type.getId() + ":" + name);
      }
    }
    return List.copyOf(ids);
  }
}
