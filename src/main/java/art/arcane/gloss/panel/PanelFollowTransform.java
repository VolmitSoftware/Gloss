package art.arcane.gloss.panel;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Objects;

public final class PanelFollowTransform {
  private PanelFollowTransform() {
  }

  public static PanelTransform resolve(PanelDefinition board, Location target) {
    return resolve(board, PanelFollowPose.from(target));
  }

  public static PanelTransform resolve(PanelDefinition board, PanelFollowPose target) {
    PanelDefinition requiredBoard = Objects.requireNonNull(board, "board");
    PanelFollowPose requiredTarget = Objects.requireNonNull(target, "target");
    PanelFollow follow = requiredBoard.follow();
    if (follow.mode() != PanelFollowMode.PLAYER) {
      return requiredBoard.transform();
    }

    PanelTransform relative = requiredBoard.transform();
    Vector offset = new Vector(relative.x(), relative.y(), relative.z());
    if (follow.rotation() == PanelFollowRotation.FULL) {
      offset.rotateAroundX(Math.toRadians(requiredTarget.pitch()));
      offset.rotateAroundY(Math.toRadians(-requiredTarget.yaw()));
    } else if (follow.rotation() == PanelFollowRotation.YAW) {
      offset.rotateAroundY(Math.toRadians(-requiredTarget.yaw()));
    }
    double yaw = follow.rotation() == PanelFollowRotation.FIXED
        ? relative.yaw()
        : requiredTarget.yaw() + relative.yaw();
    double pitch = follow.rotation() == PanelFollowRotation.FULL
        ? requiredTarget.pitch() + relative.pitch()
        : relative.pitch();
    Vector position = new Vector(requiredTarget.x(), requiredTarget.y(), requiredTarget.z()).add(offset);
    return new PanelTransform(
        requiredTarget.worldKey(),
        requiredTarget.worldUuid(),
        position.getX(),
        position.getY(),
        position.getZ(),
        yaw,
        pitch,
        relative.roll(),
        relative.scale()
    );
  }

  public static PanelTransform relativeTo(PanelTransform absolute, Location target,
                                          PanelFollowRotation rotation) {
    return relativeTo(absolute, PanelFollowPose.from(target), rotation);
  }

  public static PanelTransform relativeTo(PanelTransform absolute, PanelFollowPose target,
                                          PanelFollowRotation rotation) {
    PanelTransform requiredTransform = Objects.requireNonNull(absolute, "absolute");
    PanelFollowPose requiredTarget = Objects.requireNonNull(target, "target");
    PanelFollowRotation requiredRotation = Objects.requireNonNull(rotation, "rotation");
    if (!requiredTarget.worldUuid().equals(requiredTransform.worldUuid())) {
      throw new IllegalArgumentException("panel and follow target must be in the same world");
    }

    Vector offset = new Vector(
        requiredTransform.x() - requiredTarget.x(),
        requiredTransform.y() - requiredTarget.y(),
        requiredTransform.z() - requiredTarget.z()
    );
    if (requiredRotation == PanelFollowRotation.FULL) {
      offset.rotateAroundY(Math.toRadians(requiredTarget.yaw()));
      offset.rotateAroundX(Math.toRadians(-requiredTarget.pitch()));
    } else if (requiredRotation == PanelFollowRotation.YAW) {
      offset.rotateAroundY(Math.toRadians(requiredTarget.yaw()));
    }
    double yaw = requiredRotation == PanelFollowRotation.FIXED
        ? requiredTransform.yaw()
        : requiredTransform.yaw() - requiredTarget.yaw();
    double pitch = requiredRotation == PanelFollowRotation.FULL
        ? requiredTransform.pitch() - requiredTarget.pitch()
        : requiredTransform.pitch();
    return new PanelTransform(
        requiredTarget.worldKey(),
        requiredTarget.worldUuid(),
        offset.getX(),
        offset.getY(),
        offset.getZ(),
        yaw,
        pitch,
        requiredTransform.roll(),
        requiredTransform.scale()
    );
  }
}
