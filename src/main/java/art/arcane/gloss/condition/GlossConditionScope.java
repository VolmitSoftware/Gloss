package art.arcane.gloss.condition;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.bukkit.Placeholders;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class GlossConditionScope implements ExprScope {
    private final Gloss plugin;
    private final GlossConditionContext context;
    private final ExprScope standardScope;
    private final long nowMs;

    public GlossConditionScope(Gloss plugin, GlossConditionContext context) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.context = Objects.requireNonNull(context, "context");
        this.standardScope = plugin.text().expressionScope(context.viewer());
        this.nowMs = System.currentTimeMillis();
    }

    public static GlossConditionScope viewer(Gloss plugin, Player viewer) {
        return new GlossConditionScope(plugin, GlossConditionContext.viewer(viewer));
    }

    public static GlossConditionScope subject(Gloss plugin, Entity subject) {
        return new GlossConditionScope(plugin, GlossConditionContext.subject(subject));
    }

    @Override
    public Object variable(String name) {
        Object supplied = context.values().get(name);
        if (supplied != null) {
            return supplied;
        }
        if (name.startsWith("viewer.")) {
            return entityValue(context.viewer(), name.substring("viewer.".length()));
        }
        if (name.startsWith("subject.")) {
            return entityValue(context.subject(), name.substring("subject.".length()));
        }
        if (name.startsWith("source.")) {
            return entityValue(context.source(), name.substring("source.".length()));
        }
        if (name.startsWith("player.")) {
            return entityValue(context.viewer(), name.substring("player.".length()));
        }
        if (name.startsWith("world.")) {
            return worldValue(world(), name.substring("world.".length()));
        }
        Object timeValue = timeValue(name);
        return timeValue == null ? standardScope.variable(name) : timeValue;
    }

    @Override
    public Object call(String name, List<Object> args) {
        return switch (name) {
            case "hasPermission" -> hasPermission(args);
            case "inGroup" -> inGroup(args);
            case "inRegion" -> inRegion(args);
            case "papi" -> papi(args, false);
            case "papiNumber" -> papi(args, true);
            default -> {
                Object value = standardScope.call(name, args);
                yield value == null ? ExprFunctions.call(name, args) : value;
            }
        };
    }

    private Object entityValue(Entity entity, String property) {
        if (entity == null) {
            return null;
        }
        Object common = switch (property) {
            case "name" -> entity.getName();
            case "uuid" -> entity.getUniqueId().toString();
            case "type" -> entity.getType().getKey().getKey();
            case "world" -> entity.getWorld().getName();
            case "dead" -> entity.isDead();
            case "onGround" -> entity.isOnGround();
            case "inWater" -> entity.isInWater();
            case "fireTicks" -> (double) entity.getFireTicks();
            case "freezeTicks" -> (double) entity.getFreezeTicks();
            case "ticksLived" -> (double) entity.getTicksLived();
            default -> null;
        };
        if (common != null) {
            return common;
        }
        Object locationValue = locationValue(entity, property);
        if (locationValue != null) {
            return locationValue;
        }
        if (entity instanceof Damageable damageable) {
            Object damageableValue = damageableValue(damageable, property);
            if (damageableValue != null) {
                return damageableValue;
            }
        }
        if (entity instanceof LivingEntity living) {
            Object livingValue = livingValue(living, property);
            if (livingValue != null) {
                return livingValue;
            }
        }
        return entity instanceof Player player ? playerValue(player, property) : null;
    }

    private Object locationValue(Entity entity, String property) {
        if (!property.equals("x") && !property.equals("y") && !property.equals("z")
            && !property.equals("blockX") && !property.equals("blockY") && !property.equals("blockZ")
            && !property.equals("yaw") && !property.equals("pitch")) {
            return null;
        }
        Location location = entity.getLocation();
        return switch (property) {
            case "x" -> location.getX();
            case "y" -> location.getY();
            case "z" -> location.getZ();
            case "blockX" -> (double) location.getBlockX();
            case "blockY" -> (double) location.getBlockY();
            case "blockZ" -> (double) location.getBlockZ();
            case "yaw" -> (double) location.getYaw();
            case "pitch" -> (double) location.getPitch();
            default -> null;
        };
    }

    private Object damageableValue(Damageable damageable, String property) {
        double maxHealth = damageable.getMaxHealth();
        return switch (property) {
            case "health" -> damageable.getHealth();
            case "maxHealth" -> maxHealth;
            case "healthPercent" -> maxHealth <= 0.0D ? 0.0D : damageable.getHealth() * 100.0D / maxHealth;
            case "absorption" -> damageable.getAbsorptionAmount();
            default -> null;
        };
    }

    private Object livingValue(LivingEntity living, String property) {
        return switch (property) {
            case "ai" -> living.hasAI();
            case "gliding" -> living.isGliding();
            case "swimming" -> living.isSwimming();
            case "invisible" -> living.isInvisible();
            default -> null;
        };
    }

    private Object playerValue(Player player, String property) {
        return switch (property) {
            case "op" -> player.isOp();
            case "online" -> player.isOnline();
            case "food" -> (double) player.getFoodLevel();
            case "saturation" -> (double) player.getSaturation();
            case "level" -> (double) player.getLevel();
            case "experience" -> (double) player.getExp();
            case "totalExperience" -> (double) player.getTotalExperience();
            case "ping" -> (double) player.getPing();
            case "clientViewDistance" -> (double) player.getClientViewDistance();
            case "gameMode" -> player.getGameMode().name().toLowerCase(Locale.ROOT);
            case "locale" -> player.getLocale();
            case "sneaking" -> player.isSneaking();
            case "sprinting" -> player.isSprinting();
            case "flying" -> player.isFlying();
            case "allowFlight" -> player.getAllowFlight();
            case "group" -> plugin.groups().primaryGroupFor(player).orElse("");
            default -> null;
        };
    }

    private Object worldValue(World world, String property) {
        if (world == null) {
            return null;
        }
        return switch (property) {
            case "name" -> world.getName();
            case "uuid" -> world.getUID().toString();
            case "environment" -> world.getEnvironment().name().toLowerCase(Locale.ROOT);
            case "difficulty" -> world.getDifficulty().name().toLowerCase(Locale.ROOT);
            case "time" -> (double) world.getTime();
            case "fullTime" -> (double) world.getFullTime();
            case "storm" -> world.hasStorm();
            case "thundering" -> world.isThundering();
            case "pvp" -> world.getPVP();
            case "players" -> (double) world.getPlayers().size();
            default -> null;
        };
    }

    private Object timeValue(String name) {
        if (!name.startsWith("time.")) {
            return null;
        }
        if (name.equals("time.epochMs")) {
            return (double) nowMs;
        }
        ZonedDateTime dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
        return switch (name) {
            case "time.hour" -> (double) dateTime.getHour();
            case "time.minute" -> (double) dateTime.getMinute();
            case "time.second" -> (double) dateTime.getSecond();
            case "time.dayOfWeek" -> (double) dateTime.getDayOfWeek().getValue();
            case "time.dayOfMonth" -> (double) dateTime.getDayOfMonth();
            case "time.month" -> (double) dateTime.getMonthValue();
            default -> null;
        };
    }

    private Object hasPermission(List<Object> args) {
        Player player = rolePlayer(args, "hasPermission");
        String permission = stringArgument(args, 1, "hasPermission");
        return player != null && player.hasPermission(permission);
    }

    private Object inGroup(List<Object> args) {
        Player player = rolePlayer(args, "inGroup");
        String group = stringArgument(args, 1, "inGroup");
        return player != null && plugin.groups().primaryGroupFor(player)
            .map(value -> value.equalsIgnoreCase(group))
            .orElse(false);
    }

    private Object inRegion(List<Object> args) {
        Entity entity = roleEntity(args, "inRegion");
        String region = stringArgument(args, 1, "inRegion");
        return entity != null && ConditionWorldGuard.contains(entity.getLocation(), region);
    }

    private Object papi(List<Object> args, boolean numeric) {
        String function = numeric ? "papiNumber" : "papi";
        Player player = rolePlayer(args, function);
        String key = stringArgument(args, 1, function);
        Object fallback = args.get(2);
        if (player == null) {
            return fallback;
        }
        String token = key.startsWith("%") && key.endsWith("%") ? key : "%" + key + "%";
        String value = Placeholders.setPlaceholders(player, token);
        if (value.equals(token)) {
            return fallback;
        }
        if (!numeric) {
            return value;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private Player rolePlayer(List<Object> args, String function) {
        Entity entity = roleEntity(args, function);
        return entity instanceof Player player ? player : null;
    }

    private Entity roleEntity(List<Object> args, String function) {
        String role = stringArgument(args, 0, function);
        return switch (role) {
            case "viewer" -> context.viewer();
            case "subject" -> context.subject();
            case "source" -> context.source();
            default -> throw new IllegalArgumentException(function + " role must be viewer, subject, or source");
        };
    }

    private String stringArgument(List<Object> args, int index, String function) {
        if (args.size() <= index || !(args.get(index) instanceof String value)) {
            throw new IllegalArgumentException(function + " argument " + (index + 1) + " must be a string");
        }
        return value;
    }

    private World world() {
        Location location = context.location();
        if (location != null && location.getWorld() != null) {
            return location.getWorld();
        }
        if (context.subject() != null) {
            return context.subject().getWorld();
        }
        return context.viewer() == null ? null : context.viewer().getWorld();
    }
}
