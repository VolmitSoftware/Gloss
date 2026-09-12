package art.arcane.gloss.state;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class StateTestSupport {
    static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());
    static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());

    private StateTestSupport() {
    }

    static StateDeclarations declarations() {
        return StateDeclarations.merge(Map.of("welcome", List.of(
            new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0),
            new StateSchema("welcomed", StateScope.PLAYER, StateType.BOOLEAN, false),
            new StateSchema("weather", StateScope.WORLD, StateType.STRING, "clear"),
            new StateSchema("event", StateScope.GLOBAL, StateType.STRING, "none"))));
    }

    static Map<String, List<StateSchema>> declarationsByDocument() {
        return Map.of("welcome", List.of(
            new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0),
            new StateSchema("welcomed", StateScope.PLAYER, StateType.BOOLEAN, false),
            new StateSchema("weather", StateScope.WORLD, StateType.STRING, "clear"),
            new StateSchema("event", StateScope.GLOBAL, StateType.STRING, "none")));
    }

    static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName" -> "world";
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "World[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    static Player player(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "tester";
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0.0D, 64.0D, 0.0D);
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
