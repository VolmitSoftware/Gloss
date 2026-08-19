package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.internal.ApiMenuHandle;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.SessionActionContext;
import art.arcane.gloss.menu.components.MenuComponent;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public class MenuSession {

  private final String id;
  private final Player player;
  private final boolean freezePlayer, followPlayer;
  private final boolean closeOnDeath, closeOnTeleport;
  private final double maxDistance;
  private final double offsetDistance;
  private final List<MenuComponent<?>> components;

  private final Map<String, MenuComponent<?>> componentsById;

  private final ApiMenuHandle apiHandle;
  private final MenuSessionOptions options;

  private MenuTransform transform;
  private float scaleMultiplier;

  public MenuSession(MenuDefinitionData data, Player p, MenuSessionOptions options) {
    this.id = data.getId();
    this.player = p;
    this.options = Objects.requireNonNull(options, "options");
    this.apiHandle = options.apiHandle();
    this.scaleMultiplier = options.scaleMultiplier();
    this.freezePlayer = data.isLockPosition();
    this.followPlayer = data.isFollowPlayer();
    this.maxDistance = data.getMaxDistance();
    this.closeOnDeath = data.isCloseOnDeath();
    this.closeOnTeleport = data.isCloseOnTeleport();
    this.offsetDistance = data.getOffset().lengthSquared();

    this.transform = options.transform();
    Map<String, MenuComponent<?>> uniqueComponents = new LinkedHashMap<>(data.getComponents().size());
    for (MenuComponentData componentData : data.getComponents()) {
      MenuComponent<?> component = componentData.createComponent(this);
      if (component == null) {
        continue;
      }
      if (uniqueComponents.putIfAbsent(component.getId(), component) != null) {
        Gloss.log(Level.WARNING, "Menu \"%s\" declares duplicate component id \"%s\"; keeping the first component.",
            id, component.getId());
      }
    }
    this.components = List.copyOf(new ArrayList<>(uniqueComponents.values()));
    this.componentsById = uniqueComponents;
  }

  public String getId() {
    return id;
  }

  public Player getPlayer() {
    return player;
  }

  public boolean isFreezePlayer() {
    return freezePlayer;
  }

  public boolean isFollowPlayer() {
    return followPlayer;
  }

  public boolean isCloseOnDeath() {
    return closeOnDeath;
  }

  public boolean isCloseOnTeleport() {
    return closeOnTeleport;
  }

  public double getMaxDistance() {
    return maxDistance;
  }

  public double getOffsetDistance() {
    return offsetDistance;
  }

  public List<MenuComponent<?>> getComponents() {
    return components;
  }

  public ApiMenuHandle getApiHandle() {
    return apiHandle;
  }

  public MenuSessionOptions getOptions() {
    return options;
  }

  public MenuTransform getTransform() {
    return transform;
  }

  public float getScaleMultiplier() {
    return scaleMultiplier;
  }

  public void drainApiUpdates() {
    ApiMenuHandle handle = apiHandle;
    if (handle == null || !handle.dirty()) {
      return;
    }

    handle.drain((componentId, icon) -> {
      MenuComponent<?> component = componentsById.get(componentId);
      if (component == null) {
        return;
      }

      try {
        component.applyIcon(icon);
      } catch (Exception ex) {
        Gloss.logExceptionStack(false, ex, "Failed to apply an API icon update to component %s of menu %s for %s.",
            componentId, id, player.getName());
      }
    });
  }

  public void move(Location loc) {
    this.transform = transform.withAnchor(loc);
    components.forEach(MenuComponent::applyTransform);
  }

  public void follow(Location loc) {
    this.transform = transform.withAnchorAndFacing(loc, loc.getYaw());
    components.forEach(MenuComponent::applyTransform);
  }

  public void refreshScale() {
    this.transform = transform.withScale(scaleMultiplier * GlossConfig.current().menus().uiScale());
    components.forEach(MenuComponent::applyTransform);
  }

  public void applyTransform(MenuTransform nextTransform, float nextScaleMultiplier) {
    if (!Float.isFinite(nextScaleMultiplier) || nextScaleMultiplier <= 0F) {
      throw new IllegalArgumentException("nextScaleMultiplier must be finite and greater than zero");
    }
    this.transform = Objects.requireNonNull(nextTransform, "nextTransform");
    this.scaleMultiplier = nextScaleMultiplier;
    components.forEach(MenuComponent::applyTransform);
  }

  public void tick() {
    drainApiUpdates();
    components.forEach(MenuComponent::tick);
  }

  public void open() {
    if (options.faceViewerOnOpen()) {
      this.transform = transform.withAnchorAndFacing(transform.anchor(), player.getEyeLocation().getYaw());
    }
    components.forEach(MenuComponent::open);
  }

  public ActionContext actionContext(String componentId, HoloClickTrigger trigger) {
    return new SessionActionContext(this, componentId, options.navigator(), trigger);
  }

  public void close() {
    for (MenuComponent<?> component : components) {
      try {
        component.close();
      } catch (Exception ex) {
        Gloss.logExceptionStack(false, ex, "Failed to close menu component %s for %s.", component.getId(), player.getName());
      }
    }
  }

  public Location getCenterPoint() {
    return transform.menuOrigin();
  }

  public boolean isValid(Location loc) {
    Location centerPoint = getCenterPoint();
    return centerPoint.getWorld() != null
        && Objects.equals(loc.getWorld(), centerPoint.getWorld())
        && centerPoint.distanceSquared(loc) <= maxDistance * maxDistance + offsetDistance;
  }
}
