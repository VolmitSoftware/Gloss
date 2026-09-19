package art.arcane.gloss.api.internal;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.api.GlossInventoryClickEvent;
import art.arcane.gloss.api.GlossMenuClickEvent;
import art.arcane.gloss.api.GlossMenuCloseEvent;
import art.arcane.gloss.api.GlossMenuOpenEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

public final class ApiEvents {

  private ApiEvents() {
  }

  public static boolean fireOpen(Player player, String menuId, String ownerPluginName) {
    if (GlossMenuOpenEvent.getHandlerList().getRegisteredListeners().length == 0) {
      return true;
    }

    return !cancelled(new GlossMenuOpenEvent(player, menuId, ownerPluginName));
  }

  public static boolean fireClick(Player player, String menuId, String componentId, String ownerPluginName,
                                  HoloClickTrigger trigger) {
    if (GlossMenuClickEvent.getHandlerList().getRegisteredListeners().length == 0) {
      return true;
    }

    return !cancelled(new GlossMenuClickEvent(player, menuId, componentId, ownerPluginName, trigger));
  }

  /** A menu surface closed for this viewer. Notification only, so nothing is asked back. */
  public static void fireMenuClose(Player player, String menuId) {
    if (GlossMenuCloseEvent.getHandlerList().getRegisteredListeners().length == 0) {
      return;
    }

    dispatch(new GlossMenuCloseEvent(player, menuId));
  }

  /** An inventory-menu slot's action list finished. */
  public static void fireInventoryClick(Player player, String inventoryId, int slot) {
    if (GlossInventoryClickEvent.getHandlerList().getRegisteredListeners().length == 0) {
      return;
    }

    dispatch(new GlossInventoryClickEvent(player, inventoryId, slot));
  }

  private static void dispatch(Event event) {
    try {
      Bukkit.getPluginManager().callEvent(event);
    } catch (Throwable error) {
      Gloss.logExceptionStackThrottled(false, "api-event-dispatch", error,
          "Failed to dispatch %s.", event.getEventName());
    }
  }

  private static <T extends Event & Cancellable> boolean cancelled(T event) {
    try {
      Bukkit.getPluginManager().callEvent(event);
    } catch (Throwable error) {
      Gloss.logExceptionStackThrottled(false, "api-event-dispatch", error,
          "Failed to dispatch %s.", event.getEventName());
      return false;
    }

    return event.isCancelled();
  }
}
