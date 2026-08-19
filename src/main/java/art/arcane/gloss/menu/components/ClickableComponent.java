package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.HitboxAnchor;
import art.arcane.gloss.config.components.HitboxData;
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
  private Vector planeOrigin;

  protected CollisionPlane plane;

  protected boolean selected;

  public ClickableComponent(MenuSession session, MenuComponentData data, float highlightMod, HitboxData hitbox) {
    super(session, data);
    this.highlightMod = highlightMod;
    this.hitbox = hitbox;
  }

  public boolean isSelected() {
    return selected;
  }

  public abstract void onClick(HoloClickTrigger trigger);

  public OptionalDouble intersectionDistance(Vector origin, Vector direction) {
    if (!open || plane == null) {
      return OptionalDouble.empty();
    }
    currentIcon.orientHitbox(plane, origin);
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
  protected void onTick() {
    Location playerPos = session.getPlayer().getEyeLocation().clone();
    currentIcon.orientHitbox(plane, playerPos.toVector());
    boolean isLookingAt = plane.isLookingAt(playerPos.toVector(), playerPos.getDirection());
    if (isLookingAt && !selected) {
      this.selected = true;
      currentIcon.move(plane.getNormal().clone().multiply(highlightMod));
    } else if (!isLookingAt && selected) {
      this.selected = false;
      currentIcon.applyTransform(location);
    }
  }

  @Override
  public void onClose() {
    this.selected = false;
  }

  @Override
  public void applyTransform() {
    super.applyTransform();
    refreshPlane();
  }

  public void highlightHitbox(World w) {
    if (plane == null)
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
    positionPlane();
    currentIcon.orientHitbox(plane, session.getPlayer().getEyeLocation().toVector());
    if (selected)
      currentIcon.move(plane.getNormal().clone().multiply(highlightMod));
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
