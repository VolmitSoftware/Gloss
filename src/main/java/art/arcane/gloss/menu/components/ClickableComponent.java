package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.HitboxAnchor;
import art.arcane.gloss.config.components.HitboxData;
import art.arcane.gloss.config.components.HoverEasing;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.util.common.ParticleUtils;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

public abstract class ClickableComponent<T extends ComponentData> extends MenuComponent<T> {
  private static final double DEBUG_OUTLINE_SPACING = 0.25D;
  private static final double DEBUG_NORMAL_SPACING = 0.2D;
  private static final int DEBUG_NORMAL_POINTS = 3;

  private final float highlightMod;
  private final HitboxData hitbox;
  private final int hoverDurationTicks;
  private final HoverEasing hoverEasing;
  private Vector planeOrigin;
  private double hoverProgress;

  /** The eye position the current tick is being driven from; null outside a tick. */
  private Vector tickEye;
  /** The eye vector the plane is already oriented from, by identity. */
  private Vector orientedFrom;

  protected CollisionPlane plane;

  protected boolean selected;

  public ClickableComponent(MenuSession session, MenuComponentData data, float highlightMod,
                            HitboxData hitbox, int hoverDurationTicks, HoverEasing hoverEasing) {
    super(session, data);
    this.highlightMod = highlightMod;
    this.hitbox = hitbox;
    this.hoverDurationTicks = hoverDurationTicks;
    this.hoverEasing = hoverEasing;
  }

  public boolean isSelected() {
    return selected;
  }

  public abstract void onClick(HoloClickTrigger trigger);

  public OptionalDouble intersectionDistance(Vector origin, Vector direction) {
    if (!isInteractable() || plane == null) {
      return OptionalDouble.empty();
    }
    orientTo(origin);
    return plane.intersectionDistance(origin, direction);
  }

  @Override
  public void onOpen() {
    refreshPlane();
  }

  @Override
  protected void onIconChanged() {
    refreshPlane();
  }

  @Override
  public void tick(Vector eyeOrigin, Vector eyeDirection) {
    this.tickEye = eyeOrigin;
    try {
      super.tick(eyeOrigin, eyeDirection);
    } finally {
      this.tickEye = null;
    }
  }

  @Override
  protected void onTick(Vector eyeOrigin, Vector eyeDirection) {
    if (plane == null || currentIcon == null) {
      return;
    }
    Vector origin = eyeOrigin;
    Vector direction = eyeDirection;
    if (origin == null || direction == null) {
      Location eye = session.getPlayer().getEyeLocation();
      origin = eye.toVector();
      direction = eye.getDirection();
    }
    orientTo(origin);
    this.selected = plane.isLookingAt(origin, direction);
    advanceHover();
    applyHoverVisual();
  }

  /**
   * Orients the hitbox from an eye position, skipping the work when the plane is already oriented
   * from that exact vector. Orienting twice from one eye is a no-op by construction (pinned by
   * {@code CharacterizationHitboxOrientationTest}), so this only removes redundant work — it is
   * what collapses the geometry refresh and the tick into a single orientation.
   */
  private void orientTo(Vector eye) {
    if (plane == null || currentIcon == null || eye == null || orientedFrom == eye) {
      return;
    }
    currentIcon.orientHitbox(plane, eye);
    orientedFrom = eye;
  }

  @Override
  public void onClose() {
    this.selected = false;
    this.hoverProgress = 0D;
  }

  @Override
  public void applyTransform() {
    super.applyTransform();
    refreshPlane();
  }

  public void highlightHitbox(World w) {
    if (!isInteractable() || plane == null)
      return;
    outlinePoints(plane.getCenter(), plane.getUp(), plane.getRight(), plane.getWidth(), plane.getHeight())
        .forEach(point -> ParticleUtils.playParticle(w, point, Color.BLUE));
    normalPoints(plane.getCenter(), plane.getNormal())
        .forEach(point -> ParticleUtils.playParticle(w, point, Color.RED));
  }

  static List<Vector> outlinePoints(Vector center, Vector up, Vector right, float width, float height) {
    Vector halfUp = up.clone().multiply(height / 2D);
    Vector halfRight = right.clone().multiply(width / 2D);
    Vector downLeft = center.clone().subtract(halfUp).subtract(halfRight);
    Vector downRight = center.clone().subtract(halfUp).add(halfRight);
    Vector upRight = center.clone().add(halfUp).add(halfRight);
    Vector upLeft = center.clone().add(halfUp).subtract(halfRight);
    List<Vector> points = new ArrayList<>();
    addEdge(points, downLeft, downRight);
    addEdge(points, downRight, upRight);
    addEdge(points, upRight, upLeft);
    addEdge(points, upLeft, downLeft);
    return points;
  }

  static List<Vector> normalPoints(Vector center, Vector normal) {
    List<Vector> points = new ArrayList<>(DEBUG_NORMAL_POINTS);
    for (int index = 1; index <= DEBUG_NORMAL_POINTS; index++) {
      points.add(center.clone().add(normal.clone().multiply(index * DEBUG_NORMAL_SPACING)));
    }
    return points;
  }

  private static void addEdge(List<Vector> points, Vector from, Vector to) {
    Vector step = to.clone().subtract(from);
    int steps = Math.max(1, (int) Math.ceil(step.length() / DEBUG_OUTLINE_SPACING));
    step.multiply(1D / steps);
    for (int index = 0; index < steps; index++) {
      points.add(from.clone().add(step.clone().multiply(index)));
    }
  }

  protected CollisionPlane createHitbox() {
    return currentIcon.createBoundingBox(location);
  }

  private void refreshPlane() {
    if (currentIcon == null)
      return;
    CollisionPlane next = createHitbox();
    this.planeOrigin = next.getCenter().clone();
    if (hitbox != null && hitbox.hasCustomSize()) {
      float scale = motionUiScale();
      next.resize(hitbox.scaledWidth(scale), hitbox.scaledHeight(scale));
    }
    this.plane = next;
    this.orientedFrom = null;
    positionPlane();
    orientTo(tickEye != null ? tickEye : session.getPlayer().getEyeLocation().toVector());
    applyHoverVisual();
  }

  private void advanceHover() {
    hoverProgress = nextHoverProgress(selected, hoverProgress, hoverDurationTicks);
  }

  private void applyHoverVisual() {
    if (currentIcon == null || plane == null) {
      return;
    }
    double distance = hoverDistance(
        highlightMod,
        motionUiScale(),
        hoverProgress,
        hoverEasing
    );
    Vector offset = plane.getNormal().clone().multiply(distance);
    currentIcon.applyVisualOffset(location, offset);
  }

  static double nextHoverProgress(boolean selected, double progress, int durationTicks) {
    if (durationTicks == 0) {
      return selected ? 1D : 0D;
    }
    double step = 1D / durationTicks;
    return selected ? Math.min(1D, progress + step) : Math.max(0D, progress - step);
  }

  static double hoverDistance(float highlightModifier, float uiScale, double progress,
                              HoverEasing easing) {
    return highlightModifier * uiScale * easing.apply(progress);
  }

  private void positionPlane() {
    if (planeOrigin == null || hitbox == null)
      return;
    Vector center = hitboxCenter(
        hitbox,
        session.getTransform(),
        planeOrigin
    );
    plane.move(center.toLocation(location.getWorld()));
  }

  static Vector hitboxCenter(HitboxData hitbox, MenuTransform transform, Vector buttonOrigin) {
    Vector origin = hitbox.anchorOrDefault() == HitboxAnchor.MENU
        ? transform.menuOrigin().toVector()
        : buttonOrigin.clone();
    Vector offset = hitbox.offset() == null ? new Vector() : transform.localVector(hitbox.offset());
    return origin.add(offset);
  }

}
