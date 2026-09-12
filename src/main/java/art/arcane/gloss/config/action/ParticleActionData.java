package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.ParticleMenuAction;
import org.bukkit.NamespacedKey;

import java.util.Locale;
import java.util.Set;

public record ParticleActionData(String particle, Integer count, Double offset, String at,
                                 HoloClickTrigger trigger, String when, Integer cooldownTicks) implements MenuActionData {
  public static final Set<String> ANCHORS = Set.of("viewer", "subject", "source", "location");
  public static final int MAX_COUNT = 1024;

  public ParticleActionData {
    particle = particle == null || particle.isBlank() ? null : particle.trim().toLowerCase(Locale.ROOT);
    at = at == null || at.isBlank() ? "viewer" : at.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.PARTICLE;
  }

  public int countOrDefault() {
    return count == null ? 1 : Math.clamp(count, 1, MAX_COUNT);
  }

  public double offsetOrDefault() {
    return offset == null || !Double.isFinite(offset) ? 0.0D : Math.max(0.0D, offset);
  }

  public NamespacedKey particleKey() {
    return particle == null ? null : NamespacedKey.fromString(particle);
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new ParticleMenuAction(this);
  }

  @Override
  public String invalidReason() {
    if (particleKey() == null) {
      return "declares no particle";
    }
    return ANCHORS.contains(at) ? null : "declares at \"" + at + "\"; expected viewer, subject, source, or location";
  }
}
