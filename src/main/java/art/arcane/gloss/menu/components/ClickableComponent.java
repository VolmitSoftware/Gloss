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
import art.arcane.volmlib.util.math.MathHelper;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.OptionalDouble;

public abstract class ClickableComponent<T extends ComponentData> extends MenuComponent<T> {

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
    Vector downRight = plane.getCenter().clone().subtract(plane.getUp().clone().multiply(plane.getHeight() / 2)).add(plane.getRight().clone().multiply(plane.getWidth() / 2));
    Vector downLeft = plane.getCenter().clone().subtract(plane.getUp().clone().multiply(plane.getHeight() / 2)).subtract(plane.getRight().clone().multiply(plane.getWidth() / 2));
    Vector upRight = plane.getCenter().clone().add(plane.getUp().clone().multiply(plane.getHeight() / 2)).add(plane.getRight().clone().multiply(plane.getWidth() / 2));
    Vector upLeft = plane.getCenter().clone().add(plane.getUp().clone().multiply(plane.getHeight() / 2)).subtract(plane.getRight().clone().multiply(plane.getWidth() / 2));
    for (float d = .1F; d <= 1; d += .1F) {
      ParticleUtils.playParticle(w, MathHelper.interpolate(downRight, upRight, d), Color.BLUE);
      ParticleUtils.playParticle(w, MathHelper.interpolate(downLeft, upLeft, d), Color.BLUE);
      ParticleUtils.playParticle(w, MathHelper.interpolate(downLeft, downRight, d), Color.BLUE);
      ParticleUtils.playParticle(w, MathHelper.interpolate(upLeft, upRight, d), Color.BLUE);
      ParticleUtils.playParticle(w, MathHelper.interpolate(plane.getCenter(), plane.getCenter().clone().add(plane.getNormal().clone().multiply(2)), d), Color.RED);
    }
    ParticleUtils.playParticle(w, downRight, Color.BLUE);
    ParticleUtils.playParticle(w, downLeft, Color.BLUE);
    ParticleUtils.playParticle(w, upRight, Color.BLUE);
    ParticleUtils.playParticle(w, upLeft, Color.BLUE);
  }

  private void refreshPlane() {
    if (currentIcon == null)
      return;
    CollisionPlane next = currentIcon.createBoundingBox(location);
    this.planeOrigin = next.getCenter().clone();
    if (hitbox != null && hitbox.hasCustomSize()) {
      float scale = session.getTransform().scale();
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
        session.getTransform().scale(),
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
