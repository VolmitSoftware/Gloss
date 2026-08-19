package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import org.bukkit.NamespacedKey;

import java.util.regex.Pattern;

public record TeleportActionData(String world, Double x, Double y, Double z,
                                 Float yaw, Float pitch, HoloClickTrigger trigger) implements MenuActionData {
  private static final int MAX_WORLD_KEY_LENGTH = 255;
  private static final Pattern WORLD_KEY = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

  @Override
  public MenuActionType getType() {
    return MenuActionType.TELEPORT;
  }

  public boolean hasValidDestination() {
    return hasValidWorldKey()
        && finite(x)
        && finite(y)
        && finite(z)
        && finite(yaw)
        && finite(pitch);
  }

  public NamespacedKey resolveWorldKey() {
    return hasValidWorldKey() ? NamespacedKey.fromString(world) : null;
  }

  private boolean hasValidWorldKey() {
    return world != null
        && world.length() <= MAX_WORLD_KEY_LENGTH
        && WORLD_KEY.matcher(world).matches();
  }

  private static boolean finite(Number value) {
    return value != null && Double.isFinite(value.doubleValue());
  }
}
