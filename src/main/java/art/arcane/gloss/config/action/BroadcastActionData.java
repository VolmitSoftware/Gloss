package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.BroadcastMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.Locale;
import java.util.Set;

public record BroadcastActionData(String message, String scope, Double radius, String permission,
                                  HoloClickTrigger trigger, String when, Integer cooldownTicks) implements MenuActionData {
  public static final Set<String> SCOPES = Set.of("server", "world", "radius");

  public BroadcastActionData {
    scope = scope == null || scope.isBlank() ? "server" : scope.trim().toLowerCase(Locale.ROOT);
    permission = permission == null || permission.isBlank() ? null : permission.trim();
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.BROADCAST;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new BroadcastMenuAction(this);
  }

  @Override
  public String invalidReason() {
    if (message == null || message.isBlank()) {
      return "declares an empty broadcast message";
    }
    if (!SCOPES.contains(scope)) {
      return "declares broadcast scope \"" + scope + "\"; expected server, world, or radius";
    }
    if (scope.equals("radius") && (radius == null || !Double.isFinite(radius) || radius <= 0.0D)) {
      return "declares a radius broadcast without a positive radius";
    }
    return null;
  }
}
