package art.arcane.gloss.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired after a dialog button's action list has run. The button index is the position in the
 * rendered document, which is what an author sees in {@code /gloss dialog info}.
 */
public final class GlossDialogSubmitEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player player;
  private final String dialogId;
  private final int button;

  public GlossDialogSubmitEvent(Player player, String dialogId, int button) {
    this.player = Objects.requireNonNull(player, "player");
    this.dialogId = dialogId;
    this.button = button;
  }

  public Player getPlayer() {
    return player;
  }

  public String getDialogId() {
    return dialogId;
  }

  public int getButton() {
    return button;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
