package art.arcane.gloss.behavior;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What one trigger firing carries into an entry: the three roles, the location, the
 * {@code args.*} values, and the trigger-specific {@code selector} the entry's option filters
 * against (a region id, a material key, a chat line, a command or emit name, a menu id) plus a
 * {@code secondary} selector for menu components.
 */
public record TriggerEvent(Player viewer, Entity subject, Entity source, Location location, Map<String, Object> args,
                           String selector, String secondary) {
    public TriggerEvent {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public static TriggerEvent none() {
        return new TriggerEvent(null, null, null, null, Map.of(), null, null);
    }

    public static TriggerEvent viewer(Player player) {
        Objects.requireNonNull(player, "player");
        return new TriggerEvent(player, player, null, player.getLocation(), Map.of(), null, null);
    }

    public static TriggerEvent death(Player victim, Entity killer, String cause) {
        return new TriggerEvent(victim, victim, killer, victim.getLocation(), args("cause", cause), null, null);
    }

    public static TriggerEvent kill(Player killer, Entity victim) {
        return new TriggerEvent(killer, victim, killer, victim.getLocation(), Map.of(), typeKey(victim), null);
    }

    /** The viewer is the victim when a player, else the damager when a player, else nothing. */
    public static TriggerEvent damage(Entity victim, Entity damager, double amount, String cause) {
        Player viewer = victim instanceof Player player ? player : damager instanceof Player player ? player : null;
        Map<String, Object> args = args("amount", amount);
        args.put("cause", cause);
        return new TriggerEvent(viewer, victim, damager, victim.getLocation(), args, null, null);
    }

    public static TriggerEvent chat(Player player, String message) {
        return new TriggerEvent(player, player, null, player.getLocation(), args("message", message), message, null);
    }

    public static TriggerEvent block(Player player, String materialKey, Location location) {
        return new TriggerEvent(player, player, null, location, args("block", materialKey), materialKey, null);
    }

    public static TriggerEvent item(Player player, String materialKey, int amount) {
        Map<String, Object> args = args("material", materialKey);
        args.put("amount", (double) amount);
        return new TriggerEvent(player, player, null, player.getLocation(), args, materialKey, null);
    }

    public static TriggerEvent worldChange(Player player, String from, String to) {
        Map<String, Object> args = args("from", from);
        args.put("to", to);
        return new TriggerEvent(player, player, null, player.getLocation(), args, to, null);
    }

    public static TriggerEvent menu(Player player, String menuId, String component) {
        Map<String, Object> args = args("menu", menuId);
        if (component != null) {
            args.put("component", component);
        }
        return new TriggerEvent(player, player, null, player.getLocation(), args, menuId, component);
    }

    public static TriggerEvent region(Player player, String region) {
        return new TriggerEvent(player, player, null, player.getLocation(), args("region", region), region, null);
    }

    public static TriggerEvent emit(Player viewer, String name, Map<String, Object> args) {
        Location location = viewer == null ? null : viewer.getLocation();
        return new TriggerEvent(viewer, viewer, null, location, args, name, null);
    }

    public static TriggerEvent command(Player player, String name, Map<String, Object> args) {
        return new TriggerEvent(player, player, null, player.getLocation(), args, name, null);
    }

    public TriggerEvent withArgs(Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(args);
        merged.putAll(extra);
        return new TriggerEvent(viewer, subject, source, location, merged, selector, secondary);
    }

    /** {@code key=value} pairs become args (numbers and booleans typed); a bare word is a true flag. */
    public static Map<String, Object> parseArguments(String raw) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (String token : raw.trim().split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals <= 0) {
                parsed.put(token, Boolean.TRUE);
                continue;
            }
            parsed.put(token.substring(0, equals), typed(token.substring(equals + 1)));
        }
        return parsed;
    }

    private static Object typed(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> {
                return Boolean.TRUE;
            }
            case "false" -> {
                return Boolean.FALSE;
            }
            default -> {
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException notANumber) {
                    return value;
                }
            }
        }
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(key, value);
        return args;
    }

    private static String typeKey(Entity entity) {
        return entity.getType().getKey().getKey();
    }
}
