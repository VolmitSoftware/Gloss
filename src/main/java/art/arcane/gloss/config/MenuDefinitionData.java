package art.arcane.gloss.config;

import art.arcane.gloss.api.ParticleLayer;
import org.bukkit.util.Vector;

import java.util.List;

public class MenuDefinitionData {
  private static final double MAX_DISTANCE = 6E7;

  private final Vector offset;
  private final boolean lockPosition, followPlayer;
  private final Double maxDistance;
  private final boolean closeOnDeath, closeOnTeleport;
  private final List<MenuComponentData> components;
  private final List<ParticleLayer> particleLayers;
  private volatile String id;

  public MenuDefinitionData(Vector offset, boolean lockPosition, boolean followPlayer, Double maxDistance,
                            boolean closeOnDeath, boolean closeOnTeleport, List<MenuComponentData> components,
                            List<ParticleLayer> particleLayers) {
    this.offset = offset;
    this.lockPosition = lockPosition;
    this.followPlayer = followPlayer;
    this.maxDistance = maxDistance;
    this.closeOnDeath = closeOnDeath;
    this.closeOnTeleport = closeOnTeleport;
    this.components = components;
    this.particleLayers = ParticleLayer.copyLayers(particleLayers, "menu");
  }

  public Vector getOffset() {
    return offset;
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
