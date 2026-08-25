package art.arcane.gloss.indicator;

import com.google.common.base.Function;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageIndicatorCriticalityTest {
    @Test
    void paperDetectorReportsTheEventCriticalFlagExactly() {
        DamageIndicatorCriticality detector = DamageIndicatorCriticality.load();

        DamageIndicatorCriticality.CriticalHit critical = detector.detect(event(true));
        DamageIndicatorCriticality.CriticalHit ordinary = detector.detect(event(false));

        assertTrue(critical.known());
        assertTrue(critical.critical());
        assertTrue(ordinary.known());
        assertFalse(ordinary.critical());
    }

    @Test
    void unknownCriticalityCannotClaimACriticalHit() {
        DamageIndicatorCriticality.CriticalHit result =
            new DamageIndicatorCriticality.CriticalHit(true, false);

        assertFalse(result.known());
        assertFalse(result.critical());
    }

    private static EntityDamageByEntityEvent event(boolean critical) {
        Entity entity = proxy(Entity.class);
        DamageSource damageSource = proxy(DamageSource.class);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers =
            new EnumMap<EntityDamageEvent.DamageModifier, Double>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 4.0D);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions =
            new EnumMap<EntityDamageEvent.DamageModifier, Function<? super Double, Double>>(
                EntityDamageEvent.DamageModifier.class);
        functions.put(EntityDamageEvent.DamageModifier.BASE, ignored -> 0.0D);
        return new EntityDamageByEntityEvent(
            entity,
            entity,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            damageSource,
            modifiers,
            functions,
            critical);
    }

    private static <T> T proxy(Class<T> type) {
        Object value = Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, arguments) -> null);
        return type.cast(value);
    }
}
