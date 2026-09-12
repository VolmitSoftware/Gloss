package art.arcane.gloss.menu.action;

import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Resolves the {@code viewer}, {@code subject} and {@code source} roles of an action context. */
final class ActionRoles {
  private ActionRoles() {
  }

  static Entity entity(ActionContext context, String role) {
    ExprVariableContext roles = context.conditionScope().variableContext();
    String wanted = role == null ? "viewer" : role;
    return switch (wanted) {
      case "subject" -> roles.subject();
      case "source" -> roles.source();
      default -> roles.viewer() != null ? roles.viewer() : context.player();
    };
  }

  static Location location(ActionContext context, String anchor) {
    if ("location".equals(anchor)) {
      Location location = context.conditionScope().variableContext().location();
      if (location != null) {
        return location;
      }
    }
    Entity entity = entity(context, "location".equals(anchor) ? "viewer" : anchor);
    return entity == null ? null : entity.getLocation();
  }
}
