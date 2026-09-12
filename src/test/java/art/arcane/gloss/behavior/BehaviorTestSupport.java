package art.arcane.gloss.behavior;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BehaviorTestSupport {
    static final UUID WORLD = UUID.nameUUIDFromBytes("behavior-world".getBytes());

    private BehaviorTestSupport() {
    }

    static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> WORLD;
                case "getName" -> name;
                case "hashCode" -> name.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "World[" + name + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    static Player player(String name, Set<String> permissions, World world, double level) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, level, 64.0D, 0.0D);
                case "hasPermission" -> args[0] instanceof String node && permissions.contains(node);
                case "isOnline" -> true;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + name + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    static Zombie zombie(World world) {
        UUID id = UUID.nameUUIDFromBytes("zombie".getBytes());
        return (Zombie) Proxy.newProxyInstance(Zombie.class.getClassLoader(), new Class<?>[]{Zombie.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Zombie";
                case "getType" -> EntityType.ZOMBIE;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 5.0D, 64.0D, 5.0D);
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Zombie";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /** A context factory whose scope reads {@code level} from the viewer's x coordinate and args from the event. */
    static BehaviorDispatcher.ContextFactory contexts() {
        return (runtime, entry, event) -> new ActionContext() {
            private final ExprScope scope = new ExprScope() {
                @Override
                public Object variable(String dottedName) {
                    if (dottedName.startsWith("args.")) {
                        return event.args().get(dottedName.substring("args.".length()));
                    }
                    Player viewer = event.viewer();
                    return dottedName.equals("level") && viewer != null ? viewer.getLocation().getX() : null;
                }

                @Override
                public Object call(String name, List<Object> args) {
                    return ExprFunctions.call(name, args);
                }

                @Override
                public ExprVariableContext variableContext() {
                    Entity subject = event.subject();
                    Entity source = event.source();
                    return new ExprVariableContext(event.viewer(), subject, source, event.location());
                }
            };

            @Override
            public Player player() {
                return event.viewer();
            }

            @Override
            public String menuId() {
                return runtime.menuId();
            }

            @Override
            public String componentId() {
                return entry.componentId();
            }

            @Override
            public HoloClickTrigger trigger() {
                return HoloClickTrigger.ANY;
            }

            @Override
            public NavigationResult navigate(NavigationRequest request) {
                return NavigationResult.DENIED;
            }

            @Override
            public ExprScope conditionScope() {
                return scope;
            }
        };
    }

    static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }
}
