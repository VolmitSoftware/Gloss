package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloIcon;
import art.arcane.gloss.api.internal.ApiMenuTranslator;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.menu.icon.TextMenuIcon;
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

  protected boolean open = false;

  @SuppressWarnings("unchecked")
  public MenuComponent(MenuSession session, MenuComponentData data) {
    this.session = session;
    this.id = data.id();
    this.offset = data.offset().clone();
    this.data = (T) data.data();

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

  public void tick() {
    if (!open) return;
    if (currentIcon != null) {
      currentIcon.tick();
      refreshDynamicGeometry();
    }
    onTick();
  }

  protected abstract void onTick();

  protected abstract MenuIcon<?> createIcon();

  protected abstract void onOpen();

  protected abstract void onClose();

  public void open() {
    applyTransform();
    this.currentIcon = createIcon();
    this.currentIcon.spawn();
    this.observedGeometryRevision = currentIcon.geometryRevision();
    onOpen();
    open = true;
  }

  public void close() {
    open = false;
    if (this.currentIcon != null)
      this.currentIcon.remove();
    onClose();
  }

  public boolean applyIcon(HoloIcon icon) {
    if (!open || currentIcon == null)
      return false;

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
