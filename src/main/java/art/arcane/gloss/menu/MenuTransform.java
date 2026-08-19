package art.arcane.gloss.menu;

import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Objects;

public final class MenuTransform {

  private final Location anchor;
  private final Vector menuOffset;
  private final float facingYaw;
  private final float pitch;
  private final float roll;
  private final float scale;

  public MenuTransform(Location anchor, Vector menuOffset, float facingYaw, float pitch, float roll, float scale) {
    this.anchor = Objects.requireNonNull(anchor, "anchor").clone();
    this.menuOffset = Objects.requireNonNull(menuOffset, "menuOffset").clone();
    if (!Float.isFinite(facingYaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)) {
      throw new IllegalArgumentException("Menu rotation must be finite.");
    }
    if (!Float.isFinite(scale) || scale <= 0F) {
      throw new IllegalArgumentException("Menu scale must be finite and greater than zero.");
    }
    this.facingYaw = normalizeYaw(facingYaw);
    this.pitch = normalizeSignedAngle(pitch);
    this.roll = normalizeSignedAngle(roll);
    this.scale = scale;
  }

  public Location anchor() {
    return anchor.clone();
  }

  public float facingYaw() {
    return facingYaw;
  }

  public float layoutYaw() {
    return -facingYaw;
  }

  public float displayYaw() {
    return normalizeYaw(facingYaw + 180F);
  }

  public float pitch() {
    return pitch;
  }

  public float roll() {
    return roll;
  }

  public float scale() {
    return scale;
  }

  public MenuTransform withAnchor(Location nextAnchor) {
    return new MenuTransform(nextAnchor, menuOffset, facingYaw, pitch, roll, scale);
  }

  public MenuTransform withAnchorAndFacing(Location nextAnchor, float nextFacingYaw) {
    return new MenuTransform(nextAnchor, menuOffset, nextFacingYaw, pitch, roll, scale);
  }

  public MenuTransform withTransform(Location nextAnchor, float nextFacingYaw, float nextPitch, float nextRoll) {
    return new MenuTransform(nextAnchor, menuOffset, nextFacingYaw, nextPitch, nextRoll, scale);
  }

  public MenuTransform withScale(float nextScale) {
    return new MenuTransform(anchor, menuOffset, facingYaw, pitch, roll, nextScale);
  }

  public Location menuOrigin() {
    return localPosition(anchor, menuOffset, 1F);
  }

  public Location componentPosition(Vector componentOffset) {
    return localPosition(menuOrigin(), componentOffset, scale);
  }

  public Location localPosition(Location origin, Vector localOffset) {
    return localPosition(origin, localOffset, scale);
  }

  public Vector localVector(Vector localOffset) {
    return localVector(localOffset, scale);
  }

  public Location orient(Location location) {
    Location oriented = Objects.requireNonNull(location, "location").clone();
    oriented.setYaw(displayYaw());
    oriented.setPitch(pitch);
    return oriented;
  }

  public CollisionPlane createPlane(Vector center, float width, float height) {
    CollisionPlane plane = new CollisionPlane(center.clone(), width, height);
    plane.rotate(pitch, layoutYaw(), roll);
    return plane;
  }

  private Location localPosition(Location origin, Vector localOffset, float localScale) {
    Location position = Objects.requireNonNull(origin, "origin").clone();
    position.add(localVector(localOffset, localScale));
    return orient(position);
  }

  private Vector localVector(Vector localOffset, float localScale) {
    Vector offset = Objects.requireNonNull(localOffset, "localOffset");
    return new Vector(-offset.getX() * localScale, offset.getY() * localScale, offset.getZ() * localScale)
        .rotateAroundZ(Math.toRadians(roll))
        .rotateAroundX(Math.toRadians(pitch))
        .rotateAroundY(Math.toRadians(layoutYaw()));
  }

  private static float normalizeYaw(float yaw) {
    float normalized = yaw % 360F;
    return normalized < 0F ? normalized + 360F : normalized;
  }

  private static float normalizeSignedAngle(float angle) {
    float normalized = angle % 360F;
    if (normalized >= 180F) {
      normalized -= 360F;
    } else if (normalized < -180F) {
      normalized += 360F;
    }
    return normalized;
  }
}
