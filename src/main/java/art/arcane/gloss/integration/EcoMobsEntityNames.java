package art.arcane.gloss.integration;

import com.willfp.ecomobs.mob.EcoMob;
import com.willfp.ecomobs.mob.LivingMob;
import com.willfp.ecomobs.mob.impl.ConfigDrivenEcoMobKt;
import org.bukkit.entity.Mob;

public final class EcoMobsEntityNames {
    private EcoMobsEntityNames() {
    }

    public static String resolve(Mob entity) {
        EcoMob mob = ConfigDrivenEcoMobKt.getEcoMob(entity);
        if (mob == null) {
            return null;
        }
        LivingMob living = mob.getLivingMob(entity);
        return living == null ? null : living.getDisplayName();
    }
}
