package art.arcane.gloss.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired after an inventory-menu slot's action list has run, with the slot the viewer clicked. */
public final class GlossInventoryClickEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player player;
  private final String inventoryId;
  private final int slot;

  public GlossInventoryClickEvent(Player player, String inventoryId, int slot) {
    this.player = Objects.requireNonNull(player, "player");
    this.inventoryId = inventoryId;
    this.slot = slot;
  }

  public Player getPlayer() {
    return player;
  }

  public String getInventoryId() {
    return inventoryId;
  }

  public int getSlot() {
    return slot;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
