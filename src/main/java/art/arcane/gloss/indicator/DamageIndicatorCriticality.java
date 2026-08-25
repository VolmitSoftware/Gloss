package art.arcane.gloss.indicator;

import org.bukkit.event.entity.EntityDamageByEntityEvent;

public interface DamageIndicatorCriticality {
    CriticalHit detect(EntityDamageByEntityEvent event);

    static DamageIndicatorCriticality load() {
        try {
            EntityDamageByEntityEvent.class.getMethod("isCritical");
        } catch (NoSuchMethodException unavailable) {
            return event -> new CriticalHit(false, false);
        }
        try {
            Class<?> type = Class.forName(
                "art.arcane.gloss.paper.PaperDamageIndicatorCriticality",
                true,
                DamageIndicatorCriticality.class.getClassLoader());
            return DamageIndicatorCriticality.class.cast(type.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Paper damage criticality detector could not be loaded", failure);
        }
    }

    record CriticalHit(boolean critical, boolean known) {
        public CriticalHit {
            critical = known && critical;
        }
    }
}
