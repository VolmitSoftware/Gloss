package art.arcane.gloss.entity;

import art.arcane.gloss.BukkitRegistryStub;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.animation.AnimationService;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.hologram.HologramService;
import art.arcane.gloss.particle.ParticleService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Bukkit-free fixture for the entity overlay driver. Allocates a {@link Gloss} without running its
 * constructor, plants a server proxy, and drives the real {@link HologramService} so temporary
 * hologram counts are observable. Scheduler hops resolve inline: the server proxy reports the
 * calling thread as the owning region, which is the Paper shape of the driver.
 */
final class EntityOverlayHarness implements AutoCloseable {
    static {
        BukkitRegistryStub.install();
    }

    static final class PlayerHandle {
        final UUID uuid = UUID.randomUUID();
        final String name;
        volatile Location location;
        volatile boolean online = true;
        volatile GameMode gameMode = GameMode.SURVIVAL;
        volatile Predicate<Entity> visibility = entity -> true;
        Player proxy;

        PlayerHandle(String name) {
            this.name = name;
        }
    }

    static final class MobHandle {
        final UUID uuid = UUID.randomUUID();
        final EntityType type;
        volatile Location location;
        volatile double health = 20.0D;
        volatile double maxHealth = 20.0D;
        volatile String customName;
        volatile boolean valid = true;
        volatile boolean invisible;
        LivingEntity proxy;

        MobHandle(EntityType type) {
            this.type = type;
        }
    }

    static final class WorldState {
        final String name;
        final UUID uid;
        final List<Player> players = new CopyOnWriteArrayList<>();
        final List<MobHandle> mobs = new CopyOnWriteArrayList<>();
        World proxy;

        WorldState(String name) {
            this.name = name;
            this.uid = UUID.nameUUIDFromBytes(name.getBytes());
        }
    }

    final Map<String, WorldState> worlds = new LinkedHashMap<>();
    final List<PlayerHandle> online = new CopyOnWriteArrayList<>();
    final Gloss gloss;
    final HologramService holograms;
    final TextPipeline text;
    final AnimationService animations;
    final File dataFolder;
    volatile GlossConfig config;

    private final GlossConfigFile configFile;
    private final Object previousServer;
    private final AtomicInteger entityIds = new AtomicInteger(2000);

    EntityOverlayHarness(File dataFolder) {
        try {
            this.dataFolder = dataFolder;
            this.previousServer = serverField().get(null);
            serverField().set(null, serverProxy());

            this.gloss = allocateGloss();
            PluginDescriptionFile description = new PluginDescriptionFile("Gloss", "0.0-overlay", "art.arcane.gloss.Gloss");
            setField(gloss, JavaPlugin.class, "description", description);
            setField(gloss, JavaPlugin.class, "pluginMeta", description);
            setField(gloss, JavaPlugin.class, "dataFolder", dataFolder);
            setField(gloss, JavaPlugin.class, "isEnabled", true);
            setField(gloss, JavaPlugin.class, "logger", Logger.getAnonymousLogger());
            setField(gloss, JavaPlugin.class, "server", Bukkit.getServer());

            this.configFile = new GlossConfigFile();
            this.configFile.normalize();
            this.config = GlossConfig.from(configFile);
            this.text = new TextPipeline(gloss);
            this.animations = new AnimationService(gloss);
            SchedulerRuntime scheduler = new SchedulerRuntime(() -> gloss, Runnable::run,
                message -> {
                }, message -> {
                }, failure -> {
                    throw new IllegalStateException("Scheduler failure", failure);
                });

            setField(gloss, Gloss.class, "scheduler", scheduler);
            setField(gloss, Gloss.class, "config", config);
            setField(gloss, Gloss.class, "text", text);
            setField(gloss, Gloss.class, "animations", animations);
            setField(gloss, Gloss.class, "animator", null);
            setField(gloss, Gloss.class, "particles", new ParticleService(gloss));

            this.holograms = new HologramService(gloss);
            setField(gloss, Gloss.class, "holograms", holograms);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to build the entity overlay harness", failure);
        }
    }

    @Override
    public void close() {
        try {
            Object executor = field(holograms, HologramService.class, "fileExecutor");
            if (executor instanceof ExecutorService fileExecutor) {
                fileExecutor.shutdown();
                if (!fileExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Hologram IO executor did not quiesce");
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to quiesce the hologram IO executor", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                serverField().set(null, previousServer);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to restore the Bukkit server singleton", failure);
            }
        }
    }

    EntityOverlayService service(String document) {
        EntityOverlayService service = new EntityOverlayService(gloss);
        try {
            setField(service, EntityOverlayService.class, "started", true);
            invoke(service, "applySettings", EntityOverlayDoc.class,
                EntityOverlayDoc.parse("default.json", document));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return service;
    }

    void unloadDocument(EntityOverlayService service) {
        try {
            Method apply = EntityOverlayService.class.getDeclaredMethod("applySettings", EntityOverlayDoc.class);
            apply.setAccessible(true);
            apply.invoke(service, new Object[]{null});
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings("unchecked")
    Map<UUID, Object> map(EntityOverlayService service, String name) {
        try {
            return (Map<UUID, Object>) field(service, EntityOverlayService.class, name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    void drive(EntityOverlayService service) {
        try {
            Method drive = EntityOverlayService.class.getDeclaredMethod("drive");
            drive.setAccessible(true);
            drive.invoke(service);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    long counter(EntityOverlayService service, String name) {
        try {
            return ((java.util.concurrent.atomic.AtomicLong)
                field(service, EntityOverlayService.class, name)).get();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings("unchecked")
    Map<UUID, EntityOverlayTarget> overlays(EntityOverlayService service) {
        try {
            return (Map<UUID, EntityOverlayTarget>) field(service, EntityOverlayService.class, "overlays");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    void publishAnimationClip(String id, List<String> frames, String show) {
        try {
            AnimationClip clip = new AnimationClip(id, 100.0D, AnimationMode.ASCEND, frames);
            setField(animations, AnimationService.class, "clipsById", Map.of(id, clip));
            setField(animations, AnimationService.class, "visibility", Map.of(id, ShowCondition.of(show)));
            Object generation = field(animations, AnimationService.class, "generation");
            ((java.util.concurrent.atomic.AtomicLong) generation).incrementAndGet();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to publish a harness animation clip", failure);
        }
    }

    java.util.Set<UUID> sharedWhitelist(EntityOverlayTarget overlay) {
        return overlay.shared.whitelist;
    }

    WorldState world(String name) {
        WorldState existing = worlds.get(name);
        if (existing != null) {
            return existing;
        }
        WorldState created = new WorldState(name);
        created.proxy = worldProxy(created);
        worlds.put(name, created);
        return created;
    }

    PlayerHandle join(String name, WorldState world, double x, double y, double z) {
        PlayerHandle handle = new PlayerHandle(name);
        handle.proxy = playerProxy(handle);
        handle.location = new Location(world.proxy, x, y, z);
        world.players.add(handle.proxy);
        online.add(handle);
        return handle;
    }

    void quit(PlayerHandle handle) {
        handle.online = false;
        online.remove(handle);
        for (WorldState state : worlds.values()) {
            state.players.remove(handle.proxy);
        }
    }

    MobHandle mob(WorldState world, EntityType type, double x, double y, double z) {
        MobHandle handle = new MobHandle(type);
        handle.proxy = livingProxy(handle);
        handle.location = new Location(world.proxy, x, y, z);
        world.mobs.add(handle);
        return handle;
    }

    void despawn(WorldState world, MobHandle mob) {
        mob.valid = false;
        world.mobs.remove(mob);
    }

    private Server serverProxy() {
        InvocationHandler pluginManager = (proxy, method, args) -> switch (method.getName()) {
            case "getPlugin" -> null;
            case "getPlugins" -> new org.bukkit.plugin.Plugin[0];
            case "isPluginEnabled" -> false;
            case "registerEvents" -> null;
            default -> fallback(proxy, method, args, "PluginManager");
        };
        Object pluginManagerProxy = Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.PluginManager.class}, pluginManager);

        Object bukkitTask = Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.scheduler.BukkitTask.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getTaskId" -> 1;
                case "isCancelled" -> false;
                case "cancel" -> null;
                default -> fallback(proxy, method, args, "BukkitTask");
            });

        InvocationHandler scheduler = (proxy, method, args) -> switch (method.getName()) {
            case "runTask" -> {
                ((Runnable) args[1]).run();
                yield bukkitTask;
            }
            case "runTaskLater" -> bukkitTask;
            case "scheduleSyncDelayedTask" -> {
                if (args.length == 2 || (Long) args[2] <= 0L) {
                    ((Runnable) args[1]).run();
                }
                yield 1;
            }
            case "scheduleSyncRepeatingTask" -> 1;
            case "cancelTask", "cancelTasks" -> null;
            default -> fallback(proxy, method, args, "BukkitScheduler");
        };
        Object schedulerProxy = Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class}, scheduler);

        Logger serverLogger = Logger.getAnonymousLogger();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getOnlinePlayers" -> {
                List<Player> roster = new ArrayList<>(online.size());
                for (PlayerHandle handle : online) {
                    roster.add(handle.proxy);
                }
                yield roster;
            }
            case "getPlayer" -> {
                if (args[0] instanceof UUID id) {
                    for (PlayerHandle handle : online) {
                        if (handle.uuid.equals(id)) {
                            yield handle.proxy;
                        }
                    }
                }
                yield null;
            }
            case "getWorld" -> {
                WorldState state = args[0] instanceof String name ? worlds.get(name) : null;
                yield state == null ? null : state.proxy;
            }
            case "getWorlds" -> {
                List<World> loaded = new ArrayList<>();
                for (WorldState state : worlds.values()) {
                    loaded.add(state.proxy);
                }
                yield loaded;
            }
            case "getScheduler" -> schedulerProxy;
            case "getRegistry" -> BukkitRegistryStub.registry((Class<?>) args[0]);
            case "getPluginManager" -> pluginManagerProxy;
            case "isPrimaryThread", "isTickThread", "isGlobalTickThread", "isOwnedByCurrentRegion" -> true;
            case "isStopping" -> false;
            case "getGlobalRegionScheduler", "getRegionScheduler", "getAsyncScheduler" -> null;
            case "getLogger" -> serverLogger;
            case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "overlay-harness";
            case "getPluginsFolder" -> dataFolder;
            default -> fallback(proxy, method, args, "Server");
        };
        return (Server) Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{Server.class}, handler);
    }

    private World worldProxy(WorldState state) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> state.name;
            case "getUID" -> state.uid;
            case "getPlayers" -> List.copyOf(state.players);
            case "isChunkLoaded" -> true;
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 320;
            case "getNearbyEntities" -> nearby(state, args);
            case "spawn" -> {
                Object display = Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
                    new Class<?>[]{TextDisplay.class}, displayHandler(entityIds.getAndIncrement()));
                if (args.length >= 3 && args[2] instanceof Consumer<?> configurer) {
                    @SuppressWarnings("unchecked")
                    Consumer<TextDisplay> typed = (Consumer<TextDisplay>) configurer;
                    typed.accept((TextDisplay) display);
                }
                yield display;
            }
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "world(" + state.name + ")";
            default -> fallback(proxy, method, args, "World[" + state.name + "]");
        };
        return (World) Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{World.class}, handler);
    }

    private static List<Entity> nearby(WorldState state, Object[] args) {
        BoundingBox box = (BoundingBox) args[0];
        @SuppressWarnings("unchecked")
        Predicate<Entity> filter = args.length > 1 && args[1] != null
            ? (Predicate<Entity>) args[1] : entity -> true;
        List<Entity> found = new ArrayList<>();
        // Chunk entity slices hand back an arbitrary order; walking the roster backwards keeps the
        // fixture honest about admission having to sort rather than inherit spawn order.
        for (int index = state.mobs.size() - 1; index >= 0; index--) {
            MobHandle mob = state.mobs.get(index);
            Location at = mob.location;
            if (!box.contains(at.getX(), at.getY(), at.getZ()) || !filter.test(mob.proxy)) {
                continue;
            }
            found.add(mob.proxy);
        }
        for (Player player : state.players) {
            Location at = player.getLocation();
            if (box.contains(at.getX(), at.getY(), at.getZ()) && filter.test(player)) {
                found.add(player);
            }
        }
        return found;
    }

    private InvocationHandler displayHandler(int entityId) {
        return (proxy, method, args) -> switch (method.getName()) {
            case "getEntityId" -> entityId;
            case "getUniqueId" -> UUID.randomUUID();
            case "isValid" -> true;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "display#" + entityId;
            default -> primitiveDefault(method.getReturnType());
        };
    }

    private Player playerProxy(PlayerHandle handle) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> handle.uuid;
            case "getName" -> handle.name;
            case "isOnline", "isValid" -> handle.online;
            case "isDead" -> false;
            case "isInvisible" -> false;
            case "getGameMode" -> handle.gameMode;
            case "getLocation" -> handle.location.clone();
            case "getEyeLocation" -> handle.location.clone().add(0, 1.62D, 0);
            case "getWorld" -> handle.location.getWorld();
            case "getHeight" -> 1.8D;
            case "getType" -> EntityType.PLAYER;
            case "getHealth" -> 20.0D;
            case "getCustomName" -> null;
            case "getAttribute" -> attributeProxy(20.0D);
            case "hasPermission" -> true;
            case "canSee" -> handle.visibility.test((Entity) args[0]);
            case "showEntity", "hideEntity" -> null;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "player(" + handle.name + ")";
            default -> fallback(proxy, method, args, "Player[" + handle.name + "]");
        };
        return (Player) Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{Player.class}, handler);
    }

    private LivingEntity livingProxy(MobHandle handle) {
        Object dataContainer = Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.persistence.PersistentDataContainer.class},
            (proxy, method, args) -> method.getName().equals("get") ? null
                : fallback(proxy, method, args, "PersistentDataContainer"));
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> handle.uuid;
            case "getType" -> handle.type;
            case "getHealth" -> handle.health;
            case "getCustomName" -> handle.customName;
            case "getLocation" -> handle.location.clone();
            case "getWorld" -> handle.location.getWorld();
            case "getHeight" -> 1.0D;
            case "isValid" -> handle.valid;
            case "isDead" -> !handle.valid;
            case "isInvisible" -> handle.invisible;
            case "getAttribute" -> args[0] == Attribute.MAX_HEALTH ? attributeProxy(handle.maxHealth)
                : attributeProxy(0.0D);
            case "getPersistentDataContainer" -> dataContainer;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "mob(" + handle.type + ")";
            default -> fallback(proxy, method, args, "LivingEntity[" + handle.type + "]");
        };
        return (LivingEntity) Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{LivingEntity.class}, handler);
    }

    private static AttributeInstance attributeProxy(double value) {
        return (AttributeInstance) Proxy.newProxyInstance(EntityOverlayHarness.class.getClassLoader(),
            new Class<?>[]{AttributeInstance.class},
            (proxy, method, args) -> method.getName().equals("getValue") ? value
                : fallback(proxy, method, args, "AttributeInstance"));
    }

    private static Object fallback(Object proxy, Method method, Object[] args, String owner) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> owner;
            default -> throw new UnsupportedOperationException(owner + "." + method.getName()
                + Arrays.toString(args));
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return true;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return (char) 0;
    }

    private static Field serverField() throws ReflectiveOperationException {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        return field;
    }

    private static Gloss allocateGloss() throws ReflectiveOperationException {
        Object unsafe = unsafe();
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (Gloss) allocate.invoke(unsafe, Gloss.class);
    }

    private static Object unsafe() throws ReflectiveOperationException {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return theUnsafe.get(null);
    }

    private static void setField(Object target, Class<?> declaring, String name, Object value)
        throws ReflectiveOperationException {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(Object target, Class<?> declaring, String name)
        throws ReflectiveOperationException {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name, Class<?> parameter, Object argument)
        throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        method.invoke(target, argument);
    }
}
