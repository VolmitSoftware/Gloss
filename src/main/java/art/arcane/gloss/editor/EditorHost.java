package art.arcane.gloss.editor;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** What the editor API needs from the server it runs on. Every method may be called from any thread. */
public interface EditorHost {
  /**
   * Whether a player still holds a permission node. {@code UNKNOWN} keeps what the node grants for at most
   * {@link EditorApi#UNCONFIRMED_ADMIN} after the last confirmation.
   */
  enum Admin { ADMIN, NOT_ADMIN, UNKNOWN }

  /** {@code paper}, {@code folia}, {@code spigot}, {@code purpur} or {@code other}. */
  String platform();

  String minecraftVersion();

  String glossVersion();

  /** The server's own name for itself, used when {@code [editor] serverName} is empty. */
  String serverName();

  /** A player's name for chat lines, or a short id when the server never saw them. */
  String playerName(UUID player);

  /** One INFO line in the server log. */
  void log(String line);

  /** One WARNING line in the server log. */
  void warn(String line);

  /** Reports whether the player holds the node, online or offline where the server can tell. */
  void permission(UUID player, String node, Consumer<Admin> result);

  /** One chat line to the player, when given and online, and to every holder of {@code gloss.editor.notify}. */
  void notice(Optional<UUID> player, String line);
}
