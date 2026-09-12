package art.arcane.gloss.integration.protection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Logger;

/** Proxied Bukkit values the protection provider tests share; the world is deliberately null. */
final class ProtectionFakes {
    static final UUID PLAYER_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private ProtectionFakes() {
    }

    static Location location() {
        return new Location(null, 8.0D, 64.0D, 8.0D);
    }

    static Plugin plugin() {
        Logger logger = Logger.getLogger("gloss-protection-test");
        return (Plugin) proxy(Plugin.class, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "Gloss";
            case "isEnabled" -> true;
            case "getLogger" -> logger;
            default -> identity(proxy, method, args, "Plugin[Gloss]");
        });
    }

    /**
     * The main-hand item is null rather than an air stack: building one needs a live server, and
     * the interact provider only passes it into the event it fires.
     */
    static Player player() {
        PlayerInventory inventory = (PlayerInventory) proxy(PlayerInventory.class,
            (proxy, method, args) -> identity(proxy, method, args, "PlayerInventory[viewer]"));
        return (Player) proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "viewer";
            case "getUniqueId" -> PLAYER_ID;
            case "getInventory" -> inventory;
            case "getLocation" -> location();
            default -> identity(proxy, method, args, "Player[viewer]");
        });
    }

    static Block block() {
        return (Block) proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
            case "getLocation" -> location();
            case "getType" -> Material.CHEST;
            case "getX" -> 8;
            case "getY" -> 64;
            case "getZ" -> 8;
            default -> identity(proxy, method, args, "Block[chest]");
        });
    }

    static Entity entity() {
        return (Entity) proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> UUID.fromString("00000000-0000-4000-8000-0000000000aa");
            case "getLocation" -> location();
            default -> identity(proxy, method, args, "Entity[minecart]");
        });
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(ProtectionFakes.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object identity(Object proxy, java.lang.reflect.Method method, Object[] args, String name) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> name;
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
