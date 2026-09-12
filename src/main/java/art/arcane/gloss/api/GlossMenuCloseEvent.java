package art.arcane.gloss.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/** Fired after a Gloss menu surface closes for one viewer. Notification only; nothing reads it back. */
public final class GlossMenuCloseEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player player;
  private final String menuId;

  public GlossMenuCloseEvent(Player player, String menuId) {
    this.player = Objects.requireNonNull(player, "player");
    this.menuId = menuId;
  }

  public Player getPlayer() {
    return player;
  }

  public String getMenuId() {
    return menuId;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
