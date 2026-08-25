package art.arcane.gloss.indicator;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

record DamageIndicatorEventSnapshot(boolean damage, String cause, double reportedAmount,
                                    boolean critical, boolean criticalKnown,
                                    String directSourceType, EntityState source) {

    static DamageIndicatorEventSnapshot damage(EntityDamageEvent event, Gloss plugin,
                                                DamageIndicatorCriticality criticality) {
        EntityDamageByEntityEvent byEntity = event instanceof EntityDamageByEntityEvent damageByEntity
            ? damageByEntity
            : null;
        Entity directSource = byEntity == null ? null : byEntity.getDamager();
        Entity ownedSource = directSource != null && FoliaScheduler.isOwnedByCurrentRegion(directSource)
            ? directSource
            : null;
        DamageIndicatorCriticality.CriticalHit criticalHit = byEntity == null
            ? new DamageIndicatorCriticality.CriticalHit(false, true)
            : criticality.detect(byEntity);
        return new DamageIndicatorEventSnapshot(
            true,
            event.getCause().name().toLowerCase(Locale.ROOT),
            event.getFinalDamage(),
            criticalHit.critical(),
            criticalHit.known(),
            typeOf(ownedSource),
            EntityState.capture(ownedSource, plugin));
    }

    static DamageIndicatorEventSnapshot healing(EntityRegainHealthEvent event) {
        return new DamageIndicatorEventSnapshot(
            false,
            event.getRegainReason().name().toLowerCase(Locale.ROOT),
            event.getAmount(),
            false,
            true,
            "",
            EntityState.empty());
    }

    Map<String, Object> values(LivingEntity subject, Gloss plugin, double observedAmount) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("event.type", damage ? "damage" : "healing");
        values.put("event.cause", cause);
        values.put("event.amount", observedAmount);
        values.put("event.reportedAmount", reportedAmount);
        values.put("event.damage", damage);
        values.put("event.healing", !damage);
        values.put("event.critical", critical);
        values.put("event.criticalKnown", criticalKnown);
        values.put("event.directSourceType", directSourceType);
        EntityState.capture(subject, plugin).put(values, "subject.");
        source.put(values, "source.");
        return Map.copyOf(values);
    }

    private static String typeOf(Entity entity) {
        return entity == null ? "" : entity.getType().getKey().getKey();
    }

    record EntityState(Map<String, Object> values) {
        private static EntityState capture(Entity entity, Gloss plugin) {
            if (entity == null) {
                return empty();
            }
            Map<String, Object> values = defaults();
            Location location = entity.getLocation();
            values.put("present", true);
            values.put("uuid", entity.getUniqueId().toString());
            values.put("name", entity.getName());
            values.put("type", entity.getType().getKey().getKey());
            values.put("world", entity.getWorld().getName());
            values.put("x", location.getX());
            values.put("y", location.getY());
            values.put("z", location.getZ());
            values.put("blockX", (double) location.getBlockX());
            values.put("blockY", (double) location.getBlockY());
            values.put("blockZ", (double) location.getBlockZ());
            values.put("yaw", (double) location.getYaw());
            values.put("pitch", (double) location.getPitch());
            values.put("dead", entity.isDead());
            values.put("onGround", entity.isOnGround());
            values.put("inWater", entity.isInWater());
            values.put("fireTicks", (double) entity.getFireTicks());
            values.put("freezeTicks", (double) entity.getFreezeTicks());
            values.put("ticksLived", (double) entity.getTicksLived());
            captureDamageable(values, entity);
            captureLiving(values, entity);
            capturePlayer(values, entity, plugin);
            return new EntityState(Map.copyOf(values));
        }

        private static EntityState empty() {
            return new EntityState(Map.copyOf(defaults()));
        }

        private static Map<String, Object> defaults() {
            Map<String, Object> values = new HashMap<String, Object>();
            values.put("present", false);
            values.put("uuid", "");
            values.put("name", "");
            values.put("type", "");
            values.put("world", "");
            values.put("x", 0.0D);
            values.put("y", 0.0D);
            values.put("z", 0.0D);
            values.put("blockX", 0.0D);
            values.put("blockY", 0.0D);
            values.put("blockZ", 0.0D);
            values.put("yaw", 0.0D);
            values.put("pitch", 0.0D);
            values.put("dead", false);
            values.put("onGround", false);
            values.put("inWater", false);
            values.put("fireTicks", 0.0D);
            values.put("freezeTicks", 0.0D);
            values.put("ticksLived", 0.0D);
            values.put("health", 0.0D);
            values.put("maxHealth", 0.0D);
            values.put("healthPercent", 0.0D);
            values.put("absorption", 0.0D);
            values.put("ai", false);
            values.put("gliding", false);
            values.put("swimming", false);
            values.put("invisible", false);
            values.put("player", false);
            values.put("op", false);
            values.put("online", false);
            values.put("food", 0.0D);
            values.put("saturation", 0.0D);
            values.put("level", 0.0D);
            values.put("experience", 0.0D);
            values.put("totalExperience", 0.0D);
            values.put("ping", 0.0D);
            values.put("clientViewDistance", 0.0D);
            values.put("gameMode", "");
            values.put("locale", "");
            values.put("sneaking", false);
            values.put("sprinting", false);
            values.put("flying", false);
            values.put("allowFlight", false);
            values.put("group", "");
            return values;
        }

        private static void captureDamageable(Map<String, Object> values, Entity entity) {
            if (!(entity instanceof Damageable damageable)) {
                return;
            }
            double health = damageable.getHealth();
            double maxHealth = damageable.getMaxHealth();
            values.put("health", health);
            values.put("maxHealth", maxHealth);
            values.put("healthPercent", maxHealth <= 0.0D ? 0.0D : health * 100.0D / maxHealth);
            values.put("absorption", damageable.getAbsorptionAmount());
        }

        private static void captureLiving(Map<String, Object> values, Entity entity) {
            if (!(entity instanceof LivingEntity living)) {
                return;
            }
            values.put("ai", living.hasAI());
            values.put("gliding", living.isGliding());
            values.put("swimming", living.isSwimming());
            values.put("invisible", living.isInvisible());
        }

        private static void capturePlayer(Map<String, Object> values, Entity entity, Gloss plugin) {
            if (!(entity instanceof Player player)) {
                return;
            }
            values.put("player", true);
            values.put("op", player.isOp());
            values.put("online", player.isOnline());
            values.put("food", (double) player.getFoodLevel());
            values.put("saturation", (double) player.getSaturation());
            values.put("level", (double) player.getLevel());
            values.put("experience", (double) player.getExp());
            values.put("totalExperience", (double) player.getTotalExperience());
            values.put("ping", (double) player.getPing());
            values.put("clientViewDistance", (double) player.getClientViewDistance());
            values.put("gameMode", player.getGameMode().name().toLowerCase(Locale.ROOT));
            values.put("locale", player.getLocale());
            values.put("sneaking", player.isSneaking());
            values.put("sprinting", player.isSprinting());
            values.put("flying", player.isFlying());
            values.put("allowFlight", player.getAllowFlight());
            values.put("group", plugin == null
                ? ""
                : plugin.groups().primaryGroupFor(player).orElse(""));
        }

        private void put(Map<String, Object> target, String prefix) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                target.put(prefix + entry.getKey(), entry.getValue());
            }
        }
    }
}
