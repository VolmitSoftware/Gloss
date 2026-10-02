package art.arcane.gloss.config;

import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.condition.ShowCondition;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

public class MenuDefinitionData {
  private static final double MAX_DISTANCE = 6E7;

  private final Vector offset;
  private final boolean lockPosition, followPlayer;
  private final Double maxDistance;
  private final boolean closeOnDeath, closeOnTeleport;
  private final List<MenuComponentData> components;
  private final List<ParticleLayer> particleLayers;
  private final ShowCondition show;
  private final Map<String, String> vars;
  private final List<Variant> variants;
  private volatile String id;

  public MenuDefinitionData(Vector offset, boolean lockPosition, boolean followPlayer, Double maxDistance,
                            boolean closeOnDeath, boolean closeOnTeleport, List<MenuComponentData> components,
                            List<ParticleLayer> particleLayers, ShowCondition show, Map<String, String> vars,
                            List<Variant> variants) {
    this.offset = offset;
    this.lockPosition = lockPosition;
    this.followPlayer = followPlayer;
    this.maxDistance = maxDistance;
    this.closeOnDeath = closeOnDeath;
    this.closeOnTeleport = closeOnTeleport;
    this.components = components;
    this.particleLayers = ParticleLayer.copyLayers(particleLayers, "menu");
    this.show = show == null ? ShowCondition.ALWAYS : show;
    this.vars = vars == null ? Map.of() : Map.copyOf(vars);
    this.variants = variants;
    getVariants();
  }

  /** Declared session-variable defaults; each value is a constant expression. */
  public Map<String, String> getVars() {
    return vars == null ? Map.of() : vars;
  }

  public Vector getOffset() {
    return offset;
  }

  public ShowCondition getShow() {
    return show == null ? ShowCondition.ALWAYS : show;
  }

  public boolean isLockPosition() {
    return lockPosition;
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

  public List<MenuComponentData> getComponents() {
    return components;
  }

  public List<ParticleLayer> getParticleLayers() {
    return ParticleLayer.copyLayers(particleLayers, "menu");
  }

  public List<Variant> getVariants() {
    if (variants == null || variants.isEmpty()) {
      return List.of();
    }
    if (variants.size() > 32) {
      throw new IllegalArgumentException("a menu may declare at most 32 variants");
    }
    Set<String> ids = new HashSet<>();
    List<Variant> ordered = new ArrayList<>(variants.size());
    for (Variant variant : variants) {
      Objects.requireNonNull(variant, "menu variants must not contain null entries");
      if (!ids.add(variant.id())) {
        throw new IllegalArgumentException("duplicate menu variant id: " + variant.id());
      }
      ordered.add(variant);
    }
    ordered.sort(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id));
    return List.copyOf(ordered);
  }

  public record Variant(String id, int priority, ShowCondition when, List<MenuComponentData> components,
                        List<ParticleLayer> particleLayers) {
    public Variant {
      id = Objects.requireNonNull(id, "menu variant requires id").trim();
      if (id.isEmpty()) {
        throw new IllegalArgumentException("menu variant id must not be blank");
      }
      when = Objects.requireNonNull(when, "menu variant requires when");
      components = List.copyOf(Objects.requireNonNull(components, "menu variant requires components"));
      particleLayers = particleLayers == null ? null : ParticleLayer.copyLayers(particleLayers, "menu variant");
    }
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public double getMaxDistance() {
    return maxDistance != null ? Math.min(Math.max(maxDistance, 0), MAX_DISTANCE) : MAX_DISTANCE;
  }
}
