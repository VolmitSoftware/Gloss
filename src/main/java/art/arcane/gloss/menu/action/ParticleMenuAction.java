package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.ParticleActionData;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

public final class ParticleMenuAction extends MenuAction<ParticleActionData> {
  private volatile Particle resolved;

  public ParticleMenuAction(ParticleActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Gloss plugin = Gloss.instance;
    Location at = ActionRoles.location(context, data.at());
    if (plugin == null || at == null || at.getWorld() == null) {
      return ActionOutcome.CONTINUE;
    }
    Particle particle = particle();
    if (particle == null) {
      Gloss.warnThrottled("particle-unknown:" + data.particle(), "%s spawns the unknown particle \"%s\".",
          context.menuId(), data.particle());
      return ActionOutcome.CONTINUE;
    }
    World world = at.getWorld();
    double offset = data.offsetOrDefault();
    int count = data.countOrDefault();
    Location location = at.clone();
    FoliaScheduler.runRegion(plugin, location, () -> world.spawnParticle(particle, location, count, offset, offset, offset));
    return ActionOutcome.CONTINUE;
  }

  private Particle particle() {
    Particle current = resolved;
    if (current != null) {
      return current;
    }
    Particle found = RegistryUtil.find(Particle.class, data.particleKey());
    resolved = found;
    return found;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
