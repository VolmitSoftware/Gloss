package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.gloss.api.IconDisplayStyle;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public record ItemStackIconData(ItemStack stack, IconDisplayStyle style) implements MenuIconData {
  public ItemStackIconData {
    Objects.requireNonNull(stack, "stack");
  }

  public MenuIconType getType() {
    return MenuIconType.ITEM_STACK;
  }
}
