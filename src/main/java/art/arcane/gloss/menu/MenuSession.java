package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.internal.ApiMenuHandle;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.SessionActionContext;
import art.arcane.gloss.menu.components.ClickableComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.particle.ParticleRect;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.particle.ParticleTextLayout;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
  private final List<ParticleLayer> particleLayers;
  private final boolean readsEyePose;
  private final ShowCondition show;

  private final Map<String, MenuComponent<?>> componentsById;

  private final ApiMenuHandle apiHandle;
  private final MenuSessionOptions options;

  private MenuTransform transform;
  private float scaleMultiplier;
  private ShowCondition parentShow = ShowCondition.ALWAYS;
  private boolean active;

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
    this.show = data.getShow();

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
    this.particleLayers = data.getParticleLayers();
    this.componentsById = uniqueComponents;
    boolean eyeReader = false;
    for (MenuComponent<?> component : this.components) {
      if (component instanceof ClickableComponent<?>) {
        eyeReader = true;
        break;
      }
    }
    this.readsEyePose = eyeReader;
  }

  public String getId() {
    return id;
  }

  public Player getPlayer() {
    return player;
  }

  public boolean isFreezePlayer() {
    return freezePlayer && isShown();
  }

  public boolean isShown() {
    return active && show.matches(Gloss.instance, player) && parentShow.matches(Gloss.instance, player);
  }

  public void setParentShow(ShowCondition show) {
    parentShow = Objects.requireNonNull(show, "show");
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

  /**
   * Drives every component from one eye pose. The pose is read once per session instead of once per
   * component — and not at all when nothing in the session reads it.
   */
  public void tick() {
    if (!active) {
      return;
    }
    drainApiUpdates();
    boolean shown = isShown();
    for (MenuComponent<?> component : components) {
      component.refreshVisibility(shown);
    }
    if (!shown) {
      return;
    }
    Vector eyeOrigin = null;
    Vector eyeDirection = null;
    if (readsEyePose) {
      Location eye = player.getEyeLocation();
      eyeOrigin = eye.toVector();
      eyeDirection = eye.getDirection();
    }
    for (MenuComponent<?> component : components) {
      component.tick(eyeOrigin, eyeDirection);
    }
    emitParticleLayers();
  }

  public void open() {
    active = true;
    if (options.faceViewerOnOpen()) {
      this.transform = transform.withAnchorAndFacing(transform.anchor(), player.getEyeLocation().getYaw());
    }
    boolean shown = isShown();
    for (MenuComponent<?> component : components) {
      component.refreshVisibility(shown);
    }
  }

  public ActionContext actionContext(String componentId, HoloClickTrigger trigger) {
    return new SessionActionContext(this, componentId, options.navigator(), trigger);
  }

  public void close() {
    active = false;
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

  private void emitParticleLayers() {
    if (particleLayers.isEmpty() || !Gloss.instance.cfg().particles().enabled()) {
      return;
    }
    long tick = System.currentTimeMillis() / 50L;
    for (ParticleLayer layer : particleLayers) {
      emitParticleLayer(layer, tick);
    }
  }

  private void emitParticleLayer(ParticleLayer layer, long tick) {
    String scope = layer.target().scope();
    if (scope.equals("projection")) {
      ProjectionGeometry projection = projectionGeometry();
      if (projection != null) {
        Gloss.instance.particles().emit(player, projection.frame(), layer,
            List.of(projection.bounds()), tick);
      }
      return;
    }
    if (scope.equals("local")) {
      CollisionPlane plane = transform.createPlane(transform.menuOrigin().toVector(), 0.0F, 0.0F);
      Gloss.instance.particles().emit(player, frame(plane), layer, List.of(), tick);
      return;
    }
    for (MenuComponent<?> component : components) {
      if (layer.target().component() != null
          && !layer.target().component().equals(component.getId().toLowerCase(Locale.ROOT))) {
        continue;
      }
      CollisionPlane plane = component.particlePlane();
      if (plane == null) {
        continue;
      }
      List<ParticleRect> targets = componentTargets(component, plane, layer);
      if (!targets.isEmpty()) {
        Gloss.instance.particles().emit(player, frame(plane), layer, targets, tick);
      }
    }
  }

  private List<ParticleRect> componentTargets(MenuComponent<?> component, CollisionPlane plane,
                                               ParticleLayer layer) {
    String scope = layer.target().scope();
    if (scope.equals("component")) {
      return List.of(ParticleRect.plane(plane.getWidth(), plane.getHeight()));
    }
    ParticleText.Rendered rendered = component.particleText();
    if (rendered == null) {
      return List.of();
    }
    if (scope.equals("text")) {
      return List.of(ParticleTextLayout.textBounds(rendered.text(), transform.scale()));
    }
    if (scope.equals("line")) {
      List<ParticleRect> lines = ParticleTextLayout.lineBounds(rendered.text(), transform.scale());
      int index = layer.target().line() - 1;
      return index < lines.size() ? List.of(lines.get(index)) : List.of();
    }
    if (scope.equals("span")) {
      boolean perLetter = layer.geometry().type().equals("letterBounds")
          || layer.geometry().type().equals("glyphOutline")
          || layer.geometry().type().equals("glyphFill");
      return ParticleTextLayout.bounds(rendered, layer.target().name(), transform.scale(), perLetter);
    }
    return List.of();
  }

  private ProjectionGeometry projectionGeometry() {
    CollisionPlane basis = null;
    List<CollisionPlane> planes = new ArrayList<>();
    for (MenuComponent<?> component : components) {
      CollisionPlane plane = component.particlePlane();
      if (plane != null) {
        planes.add(plane);
        if (basis == null) {
          basis = plane;
        }
      }
    }
    if (basis == null) {
      return null;
    }
    double minimumX = Double.POSITIVE_INFINITY;
    double minimumY = Double.POSITIVE_INFINITY;
    double minimumZ = Double.POSITIVE_INFINITY;
    double maximumX = Double.NEGATIVE_INFINITY;
    double maximumY = Double.NEGATIVE_INFINITY;
    double maximumZ = Double.NEGATIVE_INFINITY;
    Vector origin = basis.getCenter();
    Vector right = basis.getRight().clone().normalize();
    Vector up = basis.getUp().clone().normalize();
    Vector back = basis.getNormal().clone().normalize().multiply(-1.0D);
    for (CollisionPlane plane : planes) {
      Vector relative = plane.getCenter().clone().subtract(origin);
      double centerX = relative.dot(right);
      double centerY = relative.dot(up);
      double centerZ = relative.dot(back);
      minimumX = Math.min(minimumX, centerX - plane.getWidth() / 2.0D);
      maximumX = Math.max(maximumX, centerX + plane.getWidth() / 2.0D);
      minimumY = Math.min(minimumY, centerY - plane.getHeight() / 2.0D);
      maximumY = Math.max(maximumY, centerY + plane.getHeight() / 2.0D);
      minimumZ = Math.min(minimumZ, centerZ);
      maximumZ = Math.max(maximumZ, centerZ);
    }
    ParticleRect bounds = new ParticleRect(
        (minimumX + maximumX) / 2.0D,
        (minimumY + maximumY) / 2.0D,
        (minimumZ + maximumZ) / 2.0D,
        maximumX - minimumX,
        maximumY - minimumY,
        maximumZ - minimumZ);
    return new ProjectionGeometry(new ParticleFrame(origin.toLocation(transform.anchor().getWorld()), right, up, back), bounds);
  }

  private ParticleFrame frame(CollisionPlane plane) {
    Vector back = plane.getNormal().clone().normalize().multiply(-1.0D);
    return new ParticleFrame(plane.getCenter().toLocation(transform.anchor().getWorld()),
        plane.getRight(), plane.getUp(), back);
  }

  private record ProjectionGeometry(ParticleFrame frame, ParticleRect bounds) {
  }
}
