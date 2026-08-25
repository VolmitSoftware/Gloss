package art.arcane.gloss.paper;

import art.arcane.gloss.indicator.DamageIndicatorCriticality;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public final class PaperDamageIndicatorCriticality implements DamageIndicatorCriticality {
    @Override
    public CriticalHit detect(EntityDamageByEntityEvent event) {
        return new CriticalHit(event.isCritical(), true);
    }
}
