package art.arcane.gloss.editor;

import java.util.Optional;

/** The answer for a permission node of a player who is not online. */
final class OfflinePermission {
  private OfflinePermission() {
  }

  /**
   * {@code byDefault}: the server's own grant, from the operator list and the node's default. {@code plugin}: a
   * permission plugin's answer where the server can ask it offline. {@code unseenPlugin}: a permission plugin runs
   * that the server cannot ask offline, which may grant the node where the server cannot see, so a no stays unknown.
   */
  static EditorHost.Admin decide(boolean byDefault, Optional<Boolean> plugin, boolean unseenPlugin) {
    if (byDefault || plugin.orElse(false)) {
      return EditorHost.Admin.ADMIN;
    }
    if (plugin.isPresent() || !unseenPlugin) {
      return EditorHost.Admin.NOT_ADMIN;
    }
    return EditorHost.Admin.UNKNOWN;
  }
}
