package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateStore;
import art.arcane.gloss.state.StateStores;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/** The owner a state write addresses: the target role's player, its world, or nothing for a global key. */
final class StateWrites {
  private StateWrites() {
  }

  static StateStore store(String key, ActionContext context) {
    StateStore store = StateStores.active();
    if (store == null) {
      return null;
    }
    if (store.declarations().get(key) == null) {
      Gloss.warnThrottled("state-undeclared:" + key,
          "%s writes the undeclared state key \"%s\"; declare it in a behavior document.", context.menuId(), key);
      return null;
    }
    return store;
  }

  /** @return the owner id, or null when the target role cannot own a key of that scope (already reported) */
  static UUID owner(StateStore store, String key, String target, ActionContext context) {
    StateSchema schema = store.declarations().get(key);
    if (schema.scope() == StateScope.GLOBAL) {
      return null;
    }
    Entity entity = ActionRoles.entity(context, target);
    if (schema.scope() == StateScope.WORLD) {
      Entity anchor = entity != null ? entity : context.player();
      return anchor == null ? null : anchor.getWorld().getUID();
    }
    if (entity instanceof Player player) {
      return player.getUniqueId();
    }
    Gloss.warnThrottled("state-target:" + key,
        "%s cannot write player state \"%s\" for %s: no player in that role.", context.menuId(), key,
        target == null ? "viewer" : target);
    return null;
  }

  static boolean missingOwner(StateSchema schema, UUID owner) {
    return schema.scope() != StateScope.GLOBAL && owner == null;
  }
}
