package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.EffectMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import org.bukkit.NamespacedKey;

import java.util.Locale;

/** Applies a potion effect; the effect key lives in {@code effect} because {@code type} names the action. */
public record EffectActionData(String effect, Integer ticks, Integer amplifier, String target,
                               HoloClickTrigger trigger, String when, Integer cooldownTicks) implements MenuActionData {
  public EffectActionData {
    effect = effect == null || effect.isBlank() ? null : effect.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.EFFECT;
  }

  public int amplifierOrDefault() {
    return amplifier == null ? 0 : Math.max(0, amplifier);
  }

  public NamespacedKey effectKey() {
    return effect == null ? null : NamespacedKey.fromString(effect);
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new EffectMenuAction(this);
  }

  @Override
  public String invalidReason() {
    if (effectKey() == null) {
      return "declares no potion effect";
    }
    if (ticks == null || ticks < 1) {
      return "declares an effect without positive ticks";
    }
    return ControlFlowLists.roleProblem(target, "target");
  }
}
