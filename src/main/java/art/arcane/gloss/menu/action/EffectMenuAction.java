package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.EffectActionData;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class EffectMenuAction extends MenuAction<EffectActionData> {
  private volatile PotionEffectType resolved;

  public EffectMenuAction(EffectActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Gloss plugin = Gloss.instance;
    Entity entity = ActionRoles.entity(context, data.target());
    if (plugin == null || !(entity instanceof LivingEntity living)) {
      return ActionOutcome.CONTINUE;
    }
    PotionEffectType type = type();
    if (type == null) {
      Gloss.warnThrottled("effect-unknown:" + data.effect(), "%s applies the unknown potion effect \"%s\".",
          context.menuId(), data.effect());
      return ActionOutcome.CONTINUE;
    }
    PotionEffect effect = new PotionEffect(type, data.ticks(), data.amplifierOrDefault());
    FoliaScheduler.runEntity(plugin, living, () -> living.addPotionEffect(effect));
    return ActionOutcome.CONTINUE;
  }

  private PotionEffectType type() {
    PotionEffectType current = resolved;
    if (current != null) {
      return current;
    }
    PotionEffectType found = RegistryUtil.find(PotionEffectType.class, data.effectKey());
    resolved = found;
    return found;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
