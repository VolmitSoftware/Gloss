package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLogger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Headless Bukkit fakes for the drop suites. Every fake counts the calls it answers so a test can
 * pin how often the engine reaches for an expensive value.
 */
final class DropFakes {

    private DropFakes() {
    }

    static ItemStack stack(Material type, int amount) {
        return new FakeStack(type, amount);
    }

    /**
     * A headless Gloss: a fake server whose scheduler captures instead of ticking, a plugin
     * allocated without its constructor but reported as enabled so the VolmLib scheduler accepts
     * work, and the two collaborators the drop-name pipeline actually calls.
     */
    static Harness harness(String configToml) {
        try {
            return new Harness(configToml);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("could not build the headless drop harness", failure);
        }
    }

    static Display display(boolean valid) {
        return (Display) Proxy.newProxyInstance(Display.class.getClassLoader(),
            new Class<?>[]{Display.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "isValid" -> valid;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Display[valid=" + valid + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    static final class Calls {
        private final Map<String, Integer> counts = new HashMap<>();

        void record(String name) {
            counts.merge(name, 1, Integer::sum);
        }

        int of(String name) {
            return counts.getOrDefault(name, 0);
        }
    }

    static final class WorldFake {
        private final Calls calls = new Calls();
        private final UUID id;
        private final List<org.bukkit.entity.Player> players;
        final World proxy;

        WorldFake(UUID id, int playerCount) {
            this.id = id;
            this.players = java.util.Collections.nCopies(playerCount, null);
            this.proxy = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, arguments) -> {
                    calls.record(method.getName());
                    return switch (method.getName()) {
                        case "getName" -> "world";
                        case "getUID" -> this.id;
                        case "getEnvironment" -> World.Environment.NORMAL;
                        case "getDifficulty" -> Difficulty.NORMAL;
                        case "getTime", "getFullTime" -> 1000L;
                        case "hasStorm", "isThundering" -> false;
                        case "getPVP" -> true;
                        case "getPlayers" -> this.players;
                        case "getBlockAt" -> block();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        case "toString" -> "World[fake]";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        }

        int calls(String name) {
            return calls.of(name);
        }

        private static Block block() {
            return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class<?>[]{Block.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getType" -> Material.AIR;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "Block[air]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
    }

    static final class ItemFake {
        private final Calls calls = new Calls();
        private final UUID id = UUID.randomUUID();
        private final Location location;
        private final PersistentDataContainer container = container();
        private ItemStack stack;
        private String customName;
        private boolean customNameVisible;
        final Item proxy;

        ItemFake(World world, ItemStack stack) {
            this.location = new Location(world, 1.5D, 64.0D, -3.5D);
            this.stack = stack;
            this.proxy = (Item) Proxy.newProxyInstance(Item.class.getClassLoader(),
                new Class<?>[]{Item.class}, (proxy, method, arguments) -> {
                    calls.record(method.getName());
                    return switch (method.getName()) {
                        case "getLocation" -> location.clone();
                        case "getWorld" -> world;
                        case "getItemStack" -> this.stack;
                        case "getThrower" -> null;
                        case "getCustomName" -> customName;
                        case "setCustomName" -> {
                            customName = (String) arguments[0];
                            yield null;
                        }
                        case "isCustomNameVisible" -> customNameVisible;
                        case "setCustomNameVisible" -> {
                            customNameVisible = (Boolean) arguments[0];
                            yield null;
                        }
                        case "getPersistentDataContainer" -> container;
                        case "getPassengers" -> List.of();
                        case "getUniqueId" -> id;
                        case "getName" -> "Fake Drop";
                        case "getType" -> EntityType.ITEM;
                        case "isValid", "isOnGround" -> true;
                        case "isInWater", "isDead" -> false;
                        case "getTicksLived" -> 7;
                        case "getPickupDelay", "getFireTicks", "getFreezeTicks" -> 0;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        case "toString" -> "Item[fake]";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        }

        void stack(ItemStack next) {
            this.stack = next;
        }

        String customName() {
            return customName;
        }

        int calls(String name) {
            return calls.of(name);
        }
    }

    static final class Harness {
        private final ServerFake server = new ServerFake();
        private final Object previousServer;
        private final Gloss previousGloss;
        private final Gloss gloss;

        private Harness(String configToml) throws ReflectiveOperationException, java.io.IOException {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            this.previousServer = serverField.get(null);
            serverField.set(null, server.proxy);

            this.gloss = (Gloss) allocate(Gloss.class);
            PluginDescriptionFile description =
                new PluginDescriptionFile("Gloss", "0.0-drop-harness", "art.arcane.gloss.Gloss");
            setField(gloss, "description", description);
            setField(gloss, "pluginMeta", description);
            setField(gloss, "server", server.proxy);
            setField(gloss, "logger", new PluginLogger(gloss));
            setField(gloss, "isEnabled", true);
            setField(gloss, "scheduler", new SchedulerRuntime(
                () -> gloss, runnable -> {
            }, message -> {
            }, message -> {
            }, failure -> {
            }));
            setField(gloss, "text", new TextPipeline(null));
            GlossConfigFile file = TomlCodec.fromToml(configToml, GlossConfigFile.class);
            file.normalize();
            setField(gloss, "config", GlossConfig.from(file));
            this.previousGloss = Gloss.instance;
            Gloss.instance = gloss;
        }

        Gloss gloss() {
            return gloss;
        }

        List<Scheduled> scheduled() {
            return List.copyOf(server.scheduled);
        }

        void runScheduled() {
            List<Scheduled> pending = List.copyOf(server.scheduled);
            server.scheduled.clear();
            for (Scheduled task : pending) {
                task.runnable().run();
            }
        }

        void restore() {
            Gloss.instance = previousGloss;
            try {
                Field serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                serverField.set(null, previousServer);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("could not restore the Bukkit server", failure);
            }
        }

        static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    // walk up
                }
            }
            throw new NoSuchFieldException(target.getClass().getName() + "#" + name);
        }

        static Object invoke(Object target, String name, Class<?>[] signature, Object... arguments) {
            try {
                for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                    try {
                        Method method = type.getDeclaredMethod(name, signature);
                        method.setAccessible(true);
                        return method.invoke(target, arguments);
                    } catch (NoSuchMethodException ignored) {
                        // walk up
                    }
                }
                throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
            } catch (ReflectiveOperationException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(name, cause);
            }
        }

        private static Object allocate(Class<?> type) throws ReflectiveOperationException {
            Constructor<?> constructor = sun.reflect.ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(type, Object.class.getDeclaredConstructor());
            return constructor.newInstance();
        }
    }

    record Scheduled(Runnable runnable, long delayTicks) {
    }

    private static final class ServerFake {
        private final List<Scheduled> scheduled = new ArrayList<>();
        private final Server proxy;

        private ServerFake() {
            Logger logger = Logger.getLogger("gloss.drop.harness");
            logger.setLevel(Level.OFF);
            logger.setUseParentHandlers(false);
            this.proxy = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[]{Server.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName", "getVersion", "getBukkitVersion" -> "drop-harness";
                    case "getLogger" -> logger;
                    case "getItemFactory" -> itemFactory();
                    case "isPrimaryThread", "isOwnedByCurrentRegion" -> true;
                    case "getScheduler" -> scheduler();
                    case "getOnlinePlayers" -> List.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "Server[drop-harness]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private Object scheduler() {
            return Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "runTask", "scheduleSyncDelayedTask" -> {
                        scheduled.add(new Scheduled((Runnable) arguments[1], 0L));
                        yield method.getReturnType() == int.class ? 1 : task();
                    }
                    case "runTaskLater" -> {
                        scheduled.add(new Scheduled((Runnable) arguments[1], (Long) arguments[2]));
                        yield task();
                    }
                    case "cancelTask", "cancelTasks" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "BukkitScheduler[drop-harness]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private static Object task() {
            return Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getTaskId" -> 1;
                    case "isSync" -> true;
                    case "isCancelled" -> false;
                    case "cancel" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "BukkitTask[drop-harness]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private static Object itemFactory() {
            return Proxy.newProxyInstance(ItemFactory.class.getClassLoader(),
                new Class<?>[]{ItemFactory.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getItemMeta")) {
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        }
    }

    static PersistentDataContainer container() {
        Map<NamespacedKey, Object> values = new HashMap<>();
        return (PersistentDataContainer) Proxy.newProxyInstance(
            PersistentDataContainer.class.getClassLoader(),
            new Class<?>[]{PersistentDataContainer.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "set" -> {
                    values.put((NamespacedKey) arguments[0], arguments[2]);
                    yield null;
                }
                case "get" -> values.get(arguments[0]);
                case "has" -> values.containsKey(arguments[0]);
                case "remove" -> {
                    values.remove(arguments[0]);
                    yield null;
                }
                case "isEmpty" -> values.isEmpty();
                case "getKeys" -> Set.copyOf(values.keySet());
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "PersistentDataContainer[drop-harness]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static final class FakeStack extends ItemStack {
        private final Material type;
        private final int amount;

        private FakeStack(Material type, int amount) {
            this.type = type;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public boolean hasItemMeta() {
            return false;
        }

        @Override
        public org.bukkit.inventory.meta.ItemMeta getItemMeta() {
            return null;
        }
    }
}
