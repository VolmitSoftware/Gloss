package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Shared fixture for the {@code Characterization*} suites in this package.
 *
 * <p>Characterization constraints (STEP 1 of the optimization program): no production file is
 * edited. The engines dereference a concrete {@link Gloss} plugin, so one is allocated without
 * running its constructor (the suite already plants {@code Bukkit.server} reflectively in
 * {@code CustomItemIntegrationTest}; this extends the same reflective-fixture pattern) and its
 * service fields are populated with real collaborators driven inline:
 * a real {@link SchedulerRuntime} whose Paper fallback path executes tasks synchronously through a
 * fake {@code BukkitScheduler} (delayed tasks are captured in a queue the tests drain), a real
 * {@link TextPipeline}, and a {@link HologramAnimator} built through its package-private test seam
 * with a recording sender (the animator worker thread is never started; passes are driven
 * manually, mirroring {@code HologramAnimatorTest}).</p>
 */
final class CharacterizationHarness implements AutoCloseable {
    static final String FAST_CLIP_LINE = "|animation.fast|";

    record DelayedTask(Runnable task, long delayTicks) {
    }

    record Sent(List<Player> viewers, int entityId, String text, TextCodec codec) {
    }

    static final class RecordingSender implements AnimationTextSender {
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(List<Player> viewers, int entityId, String legacyText, TextCodec codec) {
            sent.add(new Sent(List.copyOf(viewers), entityId, legacyText, codec));
        }
    }

    static final class DisplayHandle {
        final int entityId;
        final UUID uuid = UUID.randomUUID();
        final List<String> textHistory = new CopyOnWriteArrayList<>();
        final List<Location> teleports = new CopyOnWriteArrayList<>();
        final List<String> callLog = new CopyOnWriteArrayList<>();
        final java.util.Set<String> scoreboardTags = ConcurrentHashMap.newKeySet();
        final Map<String, Object> dataContainer = new ConcurrentHashMap<>();
        volatile String text;
        volatile Boolean visibleByDefault;
        volatile boolean removed;
        TextDisplay proxy;

        DisplayHandle(int entityId) {
            this.entityId = entityId;
        }

        String lastText() {
            return text;
        }
    }

    static final class PlayerHandle {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final Map<UUID, Boolean> perceived = new ConcurrentHashMap<>();
        final Map<UUID, Integer> showCalls = new ConcurrentHashMap<>();
        final Map<UUID, Integer> hideCalls = new ConcurrentHashMap<>();
        volatile boolean online = true;
        volatile Location location;
        Player proxy;

        PlayerHandle(String name) {
            this.name = name;
        }

        /** Empty when no visibility dispatch ever reached this player for the display. */
        Boolean perceivedVisibility(DisplayHandle display) {
            return perceived.get(display.uuid);
        }

        int showCallsFor(DisplayHandle display) {
            return showCalls.getOrDefault(display.uuid, 0);
        }

        int hideCallsFor(DisplayHandle display) {
            return hideCalls.getOrDefault(display.uuid, 0);
        }
    }

    static final class WorldState {
        final String name;
        final List<Player> players = new CopyOnWriteArrayList<>();
        final List<DisplayHandle> spawned = new CopyOnWriteArrayList<>();
        final List<Entity> chunkEntities = new CopyOnWriteArrayList<>();
        volatile boolean chunkLoaded = true;
        World proxy;
        Chunk chunkProxy;

        WorldState(String name) {
            this.name = name;
        }
    }

    final List<Throwable> schedulerErrors = new CopyOnWriteArrayList<>();
    final List<DelayedTask> delayedTasks = new CopyOnWriteArrayList<>();
    final Map<String, WorldState> worlds = new LinkedHashMap<>();
    final List<PlayerHandle> online = new CopyOnWriteArrayList<>();
    final Map<UUID, DisplayHandle> displays = new ConcurrentHashMap<>();
    final RecordingSender sender = new RecordingSender();
    final Gloss gloss;
    final HologramService service;
    final HologramAnimator animator;
    final TextPipeline text;
    final File dataFolder;
    volatile GlossConfig config;

    private final AtomicInteger entityIds = new AtomicInteger(1000);
    private final GlossConfigFile configFile;
    private final Object previousServer;

    CharacterizationHarness(File dataFolder) {
        try {
            this.dataFolder = dataFolder;
            this.previousServer = serverField().get(null);
            serverField().set(null, serverProxy());

            this.gloss = allocateGloss();
            PluginDescriptionFile description = new PluginDescriptionFile("Gloss", "0.0-characterization", "art.arcane.gloss.Gloss");
            setDeclaredField(gloss, JavaPlugin.class, "description", description);
            setDeclaredField(gloss, JavaPlugin.class, "pluginMeta", description);
            setDeclaredField(gloss, JavaPlugin.class, "dataFolder", dataFolder);
            setDeclaredField(gloss, JavaPlugin.class, "isEnabled", true);
            setDeclaredField(gloss, JavaPlugin.class, "logger", Logger.getAnonymousLogger());
            setDeclaredField(gloss, JavaPlugin.class, "server", Bukkit.getServer());

            this.configFile = new GlossConfigFile();
            this.configFile.normalize();
            this.config = GlossConfig.from(configFile);
            this.text = new TextPipeline(gloss);
            SchedulerRuntime scheduler = new SchedulerRuntime(() -> gloss, Runnable::run,
                message -> {
                }, message -> {
                }, schedulerErrors::add);

            Map<String, AnimationClip> clips = Map.of(
                "animation.fast", new AnimationClip("fast", 100.0D, AnimationMode.ASCEND, List.of("A", "B", "C", "D")));
            this.animator = new HologramAnimator(() -> config,
                name -> clips.containsKey(name) || text.hasFunction(name), clips::get, sender);

            setDeclaredField(gloss, Gloss.class, "scheduler", scheduler);
            setDeclaredField(gloss, Gloss.class, "config", config);
            setDeclaredField(gloss, Gloss.class, "text", text);
            setDeclaredField(gloss, Gloss.class, "animator", animator);

            this.service = new HologramService(gloss);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to build characterization harness", failure);
        }
    }

    @Override
    public void close() {
        try {
            // Quiesce the service's IO executor so pending document writes cannot race the
            // @TempDir cleanup (writes landing while JUnit deletes the folder).
            Object executor = declaredField(service, HologramService.class, "fileExecutor");
            if (executor instanceof java.util.concurrent.ExecutorService fileExecutor) {
                fileExecutor.shutdown();
                if (!fileExecutor.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS)) {
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
                throw new IllegalStateException("Failed to restore Bukkit server singleton", failure);
            }
        }
    }

    // ---------------------------------------------------------------- fixture state

    void configure(Consumer<GlossConfigFile> mutator) {
        mutator.accept(configFile);
        configFile.normalize();
        config = GlossConfig.from(configFile);
        try {
            setDeclaredField(gloss, Gloss.class, "config", config);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    void registerFunction(String name, Function<Player, String> resolver) {
        text.registerFunction(name, resolver);
    }

    WorldState world(String name) {
        WorldState state = worlds.get(name);
        if (state != null) {
            return state;
        }

        WorldState created = new WorldState(name);
        created.proxy = worldProxy(created);
        created.chunkProxy = chunkProxy(created);
        worlds.put(name, created);
        return created;
    }

    Location at(WorldState world, double x, double y, double z) {
        return new Location(world.proxy, x, y, z);
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

        service.prunePlayer(handle.uuid);
    }

    void moveTo(PlayerHandle handle, WorldState world, double x, double y, double z) {
        World previous = handle.location == null ? null : handle.location.getWorld();
        handle.location = new Location(world.proxy, x, y, z);
        if (previous != world.proxy) {
            for (WorldState state : worlds.values()) {
                state.players.remove(handle.proxy);
            }
            world.players.add(handle.proxy);
        }
    }

    TemporaryHologramDisplay temporary(String id, Location at, long durationMs) {
        return (TemporaryHologramDisplay) service.createTemporary(id, at, durationMs);
    }

    PersistentHologram persistent(String id, Location at) {
        return (PersistentHologram) service.create(id, at);
    }

    DisplayHandle onlySpawned(WorldState world) {
        List<DisplayHandle> live = new ArrayList<>();
        for (DisplayHandle handle : world.spawned) {
            if (!handle.removed) {
                live.add(handle);
            }
        }
        if (live.size() != 1) {
            throw new AssertionError("Expected exactly one live display in " + world.name + " but found " + live.size());
        }

        return live.get(0);
    }

    List<DisplayHandle> liveSpawned(WorldState world) {
        List<DisplayHandle> live = new ArrayList<>();
        for (DisplayHandle handle : world.spawned) {
            if (!handle.removed) {
                live.add(handle);
            }
        }

        return live;
    }

    DisplayHandle orphanDisplay(WorldState world, boolean glossTag, boolean glossMarker) {
        DisplayHandle handle = new DisplayHandle(entityIds.getAndIncrement());
        handle.proxy = displayProxy(handle);
        if (glossTag) {
            handle.scoreboardTags.add("gloss_display");
        }
        if (glossMarker) {
            handle.dataContainer.put("gloss:hologram", Boolean.TRUE);
        }
        displays.put(handle.uuid, handle);
        world.chunkEntities.add(handle.proxy);
        return handle;
    }

    void drainDelayed() {
        List<DelayedTask> snapshot = new ArrayList<>(delayedTasks);
        delayedTasks.clear();
        for (DelayedTask delayed : snapshot) {
            delayed.task().run();
        }
    }

    void fireEntitiesLoad(WorldState world) {
        try {
            Object listener = declaredField(service, HologramService.class, "listener");
            Method handler = listener.getClass().getMethod("onEntitiesLoad", EntitiesLoadEvent.class);
            handler.setAccessible(true);
            handler.invoke(listener, new EntitiesLoadEvent(world.chunkProxy, List.copyOf(world.chunkEntities)));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    void driveHolograms() {
        invokeService("driveHolograms");
    }

    void driveTemporaries() {
        invokeService("driveTemporaries");
    }

    void startTasks() {
        invokeService("startTasks");
    }

    void stopTasks() {
        invokeService("stopTasks");
    }

    @SuppressWarnings("unchecked")
    Map<UUID, Boolean> appliedVisibility(TemporaryHologramDisplay display) {
        try {
            return (Map<UUID, Boolean>) declaredField(display, TemporaryHologramDisplay.class, "appliedVisibility");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void awaitTrue(String what, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Timed out waiting for " + what);
        }
    }

    // ---------------------------------------------------------------- proxies

    private Server serverProxy() {
        InvocationHandler pluginManager = (proxy, method, args) -> switch (method.getName()) {
            case "getPlugin" -> null;
            case "getPlugins" -> new org.bukkit.plugin.Plugin[0];
            case "isPluginEnabled" -> false;
            case "registerEvents" -> null;
            default -> defaultReturn(proxy, method, args, "PluginManager");
        };
        Object pluginManagerProxy = Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.PluginManager.class}, pluginManager);

        Object bukkitTask = Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.scheduler.BukkitTask.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getTaskId" -> 1;
                case "isCancelled" -> false;
                case "cancel" -> null;
                default -> defaultReturn(proxy, method, args, "BukkitTask");
            });

        InvocationHandler scheduler = (proxy, method, args) -> switch (method.getName()) {
            case "runTask" -> {
                ((Runnable) args[1]).run();
                yield bukkitTask;
            }
            case "runTaskLater" -> {
                delayedTasks.add(new DelayedTask((Runnable) args[1], (Long) args[2]));
                yield bukkitTask;
            }
            case "scheduleSyncDelayedTask" -> {
                if (args.length == 2 || (Long) args[2] <= 0L) {
                    ((Runnable) args[1]).run();
                } else {
                    delayedTasks.add(new DelayedTask((Runnable) args[1], (Long) args[2]));
                }
                yield 1;
            }
            case "cancelTasks" -> null;
            default -> defaultReturn(proxy, method, args, "BukkitScheduler");
        };
        Object schedulerProxy = Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
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
                    yield findOnline(id);
                }
                yield findOnline(String.valueOf(args[0]));
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
            case "getPluginManager" -> pluginManagerProxy;
            case "isPrimaryThread", "isTickThread", "isGlobalTickThread", "isOwnedByCurrentRegion" -> true;
            case "getGlobalRegionScheduler", "getRegionScheduler", "getAsyncScheduler" -> null;
            case "getLogger" -> serverLogger;
            case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "characterization";
            case "getPluginsFolder" -> dataFolder;
            default -> defaultReturn(proxy, method, args, "Server");
        };
        return (Server) Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{Server.class}, handler);
    }

    private World worldProxy(WorldState state) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> state.name;
            case "getPlayers" -> List.copyOf(state.players);
            case "isChunkLoaded" -> state.chunkLoaded;
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 320;
            case "getUID" -> UUID.nameUUIDFromBytes(state.name.getBytes());
            case "getChunkAt" -> state.chunkProxy;
            case "getLoadedChunks" -> new Chunk[0];
            case "spawn" -> {
                DisplayHandle display = new DisplayHandle(entityIds.getAndIncrement());
                display.proxy = displayProxy(display);
                displays.put(display.uuid, display);
                state.spawned.add(display);
                if (args.length >= 3 && args[2] instanceof Consumer<?> configurer) {
                    @SuppressWarnings("unchecked")
                    Consumer<TextDisplay> typed = (Consumer<TextDisplay>) configurer;
                    typed.accept(display.proxy);
                }
                yield display.proxy;
            }
            default -> defaultReturn(proxy, method, args, "World[" + state.name + "]");
        };
        return (World) Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{World.class}, handler);
    }

    private Chunk chunkProxy(WorldState state) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getX", "getZ" -> 0;
            case "getWorld" -> state.proxy;
            case "getEntities" -> state.chunkEntities.toArray(new Entity[0]);
            case "isLoaded" -> state.chunkLoaded;
            default -> defaultReturn(proxy, method, args, "Chunk[" + state.name + "]");
        };
        return (Chunk) Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{Chunk.class}, handler);
    }

    private TextDisplay displayProxy(DisplayHandle handle) {
        Object dataContainer = Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{org.bukkit.persistence.PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                case "set" -> {
                    handle.dataContainer.put(String.valueOf(args[0]), args[2]);
                    yield null;
                }
                case "has" -> handle.dataContainer.containsKey(String.valueOf(args[0]));
                case "get" -> handle.dataContainer.get(String.valueOf(args[0]));
                case "remove" -> {
                    handle.dataContainer.remove(String.valueOf(args[0]));
                    yield null;
                }
                case "isEmpty" -> handle.dataContainer.isEmpty();
                default -> defaultReturn(proxy, method, args, "PersistentDataContainer");
            });

        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "getEntityId":
                    return handle.entityId;
                case "getUniqueId":
                    return handle.uuid;
                case "isValid":
                    return !handle.removed;
                case "isDead":
                    return handle.removed;
                case "setText":
                    handle.text = (String) args[0];
                    handle.textHistory.add((String) args[0]);
                    return null;
                case "getText":
                    return handle.text;
                case "teleport":
                    handle.teleports.add(((Location) args[0]).clone());
                    return true;
                case "remove":
                    handle.removed = true;
                    return null;
                case "addScoreboardTag":
                    handle.scoreboardTags.add((String) args[0]);
                    return true;
                case "getScoreboardTags":
                    return handle.scoreboardTags;
                case "getPersistentDataContainer":
                    return dataContainer;
                case "setVisibleByDefault":
                    handle.visibleByDefault = (Boolean) args[0];
                    handle.callLog.add("setVisibleByDefault(" + args[0] + ")");
                    return null;
                case "isVisibleByDefault":
                    return handle.visibleByDefault == null || handle.visibleByDefault;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "display#" + handle.entityId;
                default:
                    break;
            }
            // Tolerate every other mutator so future metadata shapes (e.g. interpolation-based
            // motion) reach the call log instead of detonating the fixture.
            if (name.startsWith("set") || name.startsWith("add")) {
                handle.callLog.add(name);
                return primitiveDefault(method.getReturnType());
            }

            return defaultReturn(proxy, method, args, "TextDisplay#" + handle.entityId);
        };
        return (TextDisplay) Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{TextDisplay.class}, handler);
    }

    private Player playerProxy(PlayerHandle handle) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> handle.uuid;
            case "getName" -> handle.name;
            case "isOnline" -> handle.online;
            case "isValid" -> handle.online;
            case "hasPermission" -> true;
            case "getLocation" -> handle.location.clone();
            case "getWorld" -> handle.location.getWorld();
            case "showEntity" -> {
                UUID target = ((Entity) args[1]).getUniqueId();
                handle.perceived.put(target, true);
                handle.showCalls.merge(target, 1, Integer::sum);
                yield null;
            }
            case "hideEntity" -> {
                UUID target = ((Entity) args[1]).getUniqueId();
                handle.perceived.put(target, false);
                handle.hideCalls.merge(target, 1, Integer::sum);
                yield null;
            }
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "player(" + handle.name + ")";
            default -> defaultReturn(proxy, method, args, "Player[" + handle.name + "]");
        };
        return (Player) Proxy.newProxyInstance(CharacterizationHarness.class.getClassLoader(),
            new Class<?>[]{Player.class}, handler);
    }

    private Player findOnline(UUID id) {
        for (PlayerHandle handle : online) {
            if (handle.uuid.equals(id)) {
                return handle.proxy;
            }
        }

        return null;
    }

    private Player findOnline(String name) {
        for (PlayerHandle handle : online) {
            if (handle.name.equals(name)) {
                return handle.proxy;
            }
        }

        return null;
    }

    private static Object defaultReturn(Object proxy, Method method, Object[] args, String owner) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return owner;
            default:
                throw new UnsupportedOperationException(owner + "." + method.getName());
        }
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

    // ---------------------------------------------------------------- reflection plumbing

    private void invokeService(String methodName) {
        try {
            Method method = HologramService.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(service);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Field serverField() throws ReflectiveOperationException {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        return field;
    }

    private static Gloss allocateGloss() throws ReflectiveOperationException {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (Gloss) allocate.invoke(unsafe, Gloss.class);
    }

    private static void setDeclaredField(Object target, Class<?> declaring, String name, Object value)
        throws ReflectiveOperationException {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object declaredField(Object target, Class<?> declaring, String name)
        throws ReflectiveOperationException {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
