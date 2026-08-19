package art.arcane.gloss.panel;

import java.util.Objects;
import java.util.UUID;

public record PanelFollow(PanelFollowMode mode, UUID targetPlayerUuid, PanelFollowRotation rotation) {
  public PanelFollow {
    mode = Objects.requireNonNull(mode, "mode");
    rotation = Objects.requireNonNull(rotation, "rotation");

    if (mode == PanelFollowMode.NONE) {
      if (targetPlayerUuid != null || rotation != PanelFollowRotation.FIXED) {
        throw new IllegalArgumentException("non-following panels cannot declare a target or rotation mode");
      }
    } else if (targetPlayerUuid == null) {
      throw new IllegalArgumentException("player-following panels require targetPlayerUuid");
    }
  }

  public static PanelFollow none() {
    return new PanelFollow(PanelFollowMode.NONE, null, PanelFollowRotation.FIXED);
  }

  public static PanelFollow player(UUID targetPlayerUuid, PanelFollowRotation rotation) {
    return new PanelFollow(PanelFollowMode.PLAYER, targetPlayerUuid, rotation);
  }
}
