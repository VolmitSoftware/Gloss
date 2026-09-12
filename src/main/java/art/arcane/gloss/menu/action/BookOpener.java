package art.arcane.gloss.menu.action;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The Bukkit-typed contract a Paper book bridge implements. {@code Player#openBook(ItemStack)} takes
 * and returns no Kyori type, so it survives the shaded jar's relocation; Spigot has no such method
 * and takes the packet path instead.
 */
public interface BookOpener {
    boolean open(Player viewer, ItemStack book);
}
