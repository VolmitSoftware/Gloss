package art.arcane.gloss.api;

import java.util.List;
import java.util.Objects;

public record HoloMenu(String id, double offsetX, double offsetY, double offsetZ, boolean lockPosition,
                       boolean followPlayer, double maxDistance, boolean closeOnDeath, boolean closeOnTeleport,
                       List<HoloComponent> components, List<ParticleLayer> particleLayers) {
  public HoloMenu {
    id = HoloText.sanitizeId(id);
    Objects.requireNonNull(components, "components");
    components = List.copyOf(components);
    HoloText.requireDistinctIds(components);
    particleLayers = ParticleLayer.copyLayers(particleLayers, "API holographic menu");
  }

  public static HoloMenuBuilder builder() {
    return new HoloMenuBuilder();
  }
}
