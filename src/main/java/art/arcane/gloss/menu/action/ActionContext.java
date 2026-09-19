package art.arcane.gloss.menu.action;

import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;

import art.arcane.gloss.api.HoloClickTrigger;
import org.bukkit.entity.Player;

public interface ActionContext {
  Player player();

  String menuId();

  String componentId();

  HoloClickTrigger trigger();

  NavigationResult navigate(NavigationRequest request);

  /** The scope a per-action {@code when} gate evaluates in; defaults to the clicking player's condition scope. */
  default ExprScope conditionScope() {
    return GlossConditionScope.viewer(Gloss.instance, player());
  }

  /** The session variables of the surface this click came from, or null when it carries none. */
  default SessionVariables sessionVariables() {
    return null;
  }

  /**
   * Closes the surface this click came from. Each surface knows how: a menu session ends, an
   * inventory window closes. A context with nothing to close does nothing.
   */
  default void closeSurface(Player player) {
  }

  /**
   * True when a null {@link #player()} means this run has no viewer at all, so an action that
   * targets a player must skip instead of dereferencing it. A click always has a clicker; a
   * behavior entry on a global interval, on server start or from an API emit does not.
   */
  default boolean viewerless() {
    return false;
  }
}
