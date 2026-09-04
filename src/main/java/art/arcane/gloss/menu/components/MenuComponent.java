package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloIcon;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.api.internal.ApiMenuTranslator;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.menu.icon.TextMenuIcon;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.util.Vector;

public abstract class MenuComponent<T extends ComponentData> {

  protected final MenuSession session;
  protected final Vector offset;
  protected final T data;

  protected final String id;

  protected Location location;
  protected MenuIcon<?> currentIcon;
  private long observedGeometryRevision;
  private final ShowCondition show;
  private HoloIcon pendingIcon;

  protected boolean open = false;

  @SuppressWarnings("unchecked")
  public MenuComponent(MenuSession session, MenuComponentData data) {
    this.session = session;
    this.id = data.id();
    this.offset = data.offset().clone();
    this.data = (T) data.data();
    this.show = data.show();

    this.location = session.getTransform().componentPosition(offset);
  }

  public String getId() {
    return id;
  }

  public Location getLocation() {
    return location;
  }

  public boolean isOpen() {
    return open;
  }

  public boolean isInteractable() {
    return open && session.isShown() && show.matches(Gloss.instance, session.getPlayer());
  }

  public void refreshVisibility(boolean parentShown) {
    if (parentShown && show.matches(Gloss.instance, session.getPlayer())) {
      if (!open) {
        open();
      }
    } else if (open) {
      hide();
    }
  }

  public CollisionPlane particlePlane() {
    if (!open || currentIcon == null) {
      return null;
    }
    CollisionPlane plane = currentIcon.createBoundingBox(location);
    currentIcon.orientHitbox(plane, session.getPlayer().getEyeLocation().toVector());
    return plane;
  }

  public ParticleText.Rendered particleText() {
    return currentIcon == null ? null : currentIcon.particleText();
  }

  /**
   * Ticks with the eye pose the session read once for this pass, instead of every component
   * reading it again.
   *
   * @param eyeOrigin    the viewer's eye position for this tick, or null when the session holds
   *                     nothing that reads the eye pose
   * @param eyeDirection the viewer's look direction for this tick, on the same terms
   */
  public void tick(Vector eyeOrigin, Vector eyeDirection) {
    if (!open) return;
    if (currentIcon != null) {
      currentIcon.tick();
      refreshDynamicGeometry();
    }
    onTick(eyeOrigin, eyeDirection);
  }

  protected abstract void onTick(Vector eyeOrigin, Vector eyeDirection);

  protected abstract MenuIcon<?> createIcon();

  protected abstract void onOpen();

  protected abstract void onClose();

  public void open() {
    if (open || !session.isShown() || !show.matches(Gloss.instance, session.getPlayer())) {
      return;
    }
    applyTransform();
    if (pendingIcon != null) {
      this.currentIcon = MenuIcon.createIcon(session, location, ApiMenuTranslator.iconData(pendingIcon), this);
      pendingIcon = null;
    } else if (currentIcon == null) {
      this.currentIcon = createIcon();
    }
    this.currentIcon.spawn();
    this.observedGeometryRevision = currentIcon.geometryRevision();
    onOpen();
    open = true;
  }

  public void close() {
    hide();
    currentIcon = null;
    pendingIcon = null;
  }

  public boolean applyIcon(HoloIcon icon) {
    if (!open) {
      pendingIcon = icon;
      return true;
    }
    if (currentIcon == null) {
      return false;
    }

    if (icon instanceof HoloIcon.Text text
        && currentIcon instanceof TextMenuIcon textIcon
        && textIcon.updateText(text.miniMessage())) {
      currentIcon.applyTransform(location);
      onIconChanged();
      observedGeometryRevision = currentIcon.geometryRevision();
      return true;
    }

    MenuIcon<?> replacement = MenuIcon.createIcon(session, location, ApiMenuTranslator.iconData(icon), this);
    if (replacement == null)
      return false;

    swapIcon(replacement);
    return true;
  }

  protected void swapIcon(MenuIcon<?> icon) {
    this.currentIcon.remove();
    this.currentIcon = icon;
    this.currentIcon.applyTransform(location);
    this.observedGeometryRevision = currentIcon.geometryRevision();
    onIconChanged();
    this.currentIcon.spawn();
  }

  protected void onIconChanged() {
  }

  public void applyTransform() {
    this.location = session.getTransform().componentPosition(offset);
    if (this.currentIcon != null)
      this.currentIcon.applyTransform(location);
  }

  private void hide() {
    open = false;
    if (currentIcon != null) {
      currentIcon.remove();
    }
    onClose();
  }

  private void refreshDynamicGeometry() {
    long revision = currentIcon.geometryRevision();
    if (revision == observedGeometryRevision) {
      return;
    }
    currentIcon.applyTransform(location);
    onIconChanged();
    observedGeometryRevision = revision;
  }
}
