package art.arcane.gloss.state;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.GlossStateAccess;
import art.arcane.gloss.behavior.EmitBus;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.service.GlossService;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Persisted state for behaviors and for any other lane that keeps per-player data. Values live in
 * memory; {@code state/global.json}, {@code state/worlds/<uuid>.json} and
 * {@code state/players/<uuid>.json} are written by the flush task, on quit and on disable, never on
 * the thread that mutated them. A player's file is read on join off the game thread and applied on
 * the player's region; until it lands, reads answer the declared default and writes queue.
 */
public final class StateStore implements GlossService, Listener {
    public static final String NAME = "state";
    private static final UUID GLOBAL_OWNER = new UUID(0L, 0L);
    private static final long DISABLE_FLUSH_SECONDS = 5L;

    /** Runs a task on the region that owns {@code player}; the store never touches state from elsewhere. */
    @FunctionalInterface
    public interface RegionDispatch {
        void run(UUID player, Runnable task);
    }

    private final Gloss plugin;
    private final Path root;
    private final Executor io;
    private final RegionDispatch regions;
    private final ExecutorService ownedIo;
    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> worlds = new ConcurrentHashMap<>();
    private final Entry global = new Entry();
    private final Set<FileKey> dirty = ConcurrentHashMap.newKeySet();
    private final Map<String, List<StateSchema>> documentSources = new ConcurrentHashMap<>();
    private final Map<String, List<StateSchema>> externalSources = new ConcurrentHashMap<>();
    private volatile StateDeclarations declarations = StateDeclarations.empty();
    private volatile boolean quitForgetDeferred;
    private int flushTaskId = -1;

    public StateStore(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.root = new File(plugin.getDataFolder(), NAME).toPath();
        this.ownedIo = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Gloss-State-IO");
            thread.setDaemon(true);
            return thread;
        });
        this.io = ownedIo;
        this.regions = this::dispatchToPlayer;
        global.loaded = true;
    }

    public StateStore(Path root, Executor io, RegionDispatch regions) {
        this.plugin = null;
        this.root = Objects.requireNonNull(root, "root");
        this.io = Objects.requireNonNull(io, "io");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.ownedIo = null;
        global.loaded = true;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExprVariableNamespaces.global().register(new StateNamespace(this));
        ExprFunctionRegistry.global().register(StateOfFunction.spec(this));
    }

    @Override
    public void enable() {
        global.values.putAll(StateFiles.read(globalFile()));
        loadWorldFiles();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            beginLoad(player.getUniqueId());
        }
        int intervalTicks = Math.max(20, plugin.cfg().modules().behaviors().stateFlushSeconds() * 20);
        flushTaskId = plugin.scheduler().ar(this::flush, intervalTicks);
        GlossStateAccess.set(new GlossStateBridge(this, (name, args) -> EmitBus.emit(name, args, null)));
        StateStores.install(this);
    }

    @Override
    public void disable() {
        if (flushTaskId != -1) {
            plugin.scheduler().car(flushTaskId);
            flushTaskId = -1;
        }
        HandlerList.unregisterAll(this);
        StateStores.install(null);
        GlossStateAccess.set(null);
        ExprVariableNamespaces.global().unregister(StateNamespace.PREFIX);
        ExprFunctionRegistry.global().unregister(StateOfFunction.NAME);
        flush();
        awaitIo();
        players.clear();
        worlds.clear();
        global.values.clear();
        dirty.clear();
    }

    /** Replaces every document declaration; plugin declarations stay. Throws on a conflict and keeps the old schema. */
    public synchronized void declare(Map<String, List<StateSchema>> byDocument) {
        Map<String, List<StateSchema>> next = new LinkedHashMap<>(Objects.requireNonNull(byDocument, "byDocument"));
        StateDeclarations merged = merge(next, externalSources);
        documentSources.clear();
        documentSources.putAll(next);
        declarations = merged;
    }

    public synchronized void declareExternal(String owner, List<StateSchema> schemas) {
        Map<String, List<StateSchema>> next = new LinkedHashMap<>(externalSources);
        next.put(Objects.requireNonNull(owner, "owner"), List.copyOf(schemas));
        StateDeclarations merged = merge(documentSources, next);
        externalSources.put(owner, List.copyOf(schemas));
        declarations = merged;
    }

    /** Declares {@code schema} under {@code owner} unless a conflicting declaration exists; true when the key is usable. */
    public synchronized boolean ensureDeclared(String owner, StateSchema schema) {
        StateSchema existing = declarations.get(schema.key());
        if (existing != null) {
            return existing.agreesWith(schema);
        }
        try {
            declareExternal(owner, List.of(schema));
            return true;
        } catch (StateConflictException conflict) {
            return false;
        }
    }

    private static StateDeclarations merge(Map<String, List<StateSchema>> documents, Map<String, List<StateSchema>> external) {
        Map<String, List<StateSchema>> all = new LinkedHashMap<>(documents);
        all.putAll(external);
        return StateDeclarations.merge(all);
    }

    public StateDeclarations declarations() {
        return declarations;
    }

    /**
     * The schemas plugins and running scenes declared, by owner. A document set is checked against
     * these before it is accepted, so a document that collides with a scene's {@code once} key is
     * refused by name instead of taking the whole reload with it.
     */
    public Map<String, List<StateSchema>> externalDeclarations() {
        return Map.copyOf(externalSources);
    }

    public Object get(StateScope scope, UUID owner, String key) {
        StateSchema schema = declarations.get(key);
        if (schema == null || schema.scope() != scope) {
            return null;
        }
        Entry entry = entry(scope, owner, false);
        if (entry == null || !entry.loaded) {
            return schema.defaultValue();
        }
        Object value = entry.values.get(key);
        return value == null ? schema.defaultValue() : value;
    }

    public void set(StateScope scope, UUID owner, String key, Object value) {
        StateSchema schema = require(scope, key);
        Object coerced = schema.type().coerce(value);
        mutate(scope, owner, entry -> entry.values.put(key, coerced));
    }

    public void add(StateScope scope, UUID owner, String key, double delta) {
        StateSchema schema = require(scope, key);
        if (schema.type() != StateType.NUMBER) {
            throw new IllegalArgumentException("state key " + key + " is a " + schema.type().key() + ", not a number");
        }
        mutate(scope, owner, entry -> {
            Object current = entry.values.get(key);
            double base = current instanceof Number number ? number.doubleValue() : (Double) schema.defaultValue();
            entry.values.put(key, base + delta);
        });
    }

    public void clear(StateScope scope, UUID owner, String key) {
        require(scope, key);
        mutate(scope, owner, entry -> entry.values.remove(key));
    }

    /** A copy of another lane's section of the player's file; empty when absent or not yet loaded. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> section(UUID player, String name) {
        Entry entry = players.get(Objects.requireNonNull(player, "player"));
        if (entry == null || !entry.loaded) {
            return new LinkedHashMap<>();
        }
        Object value = entry.values.get(name);
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    public void writeSection(UUID player, String name, Map<String, Object> values) {
        Objects.requireNonNull(name, "name");
        Map<String, Object> copy = new LinkedHashMap<>(Objects.requireNonNull(values, "values"));
        mutate(StateScope.PLAYER, player, entry -> entry.values.put(name, copy));
    }

    public Map<String, Object> snapshot(StateScope scope, UUID owner) {
        Entry entry = entry(scope, owner, false);
        return entry == null ? Map.of() : new LinkedHashMap<>(entry.values);
    }

    public boolean loaded(UUID player) {
        Entry entry = players.get(player);
        return entry != null && entry.loaded;
    }

    /**
     * Runs {@code task} on the player's region once their file has landed, immediately when it
     * already has or when no read is in flight. Until the file lands every {@code state.*} read
     * answers the declared default, which is indistinguishable from a genuine first visit - so a
     * JOIN gate has to wait for this rather than read through it.
     */
    public void whenLoaded(UUID player, Runnable task) {
        Objects.requireNonNull(task, "task");
        Entry entry = players.get(Objects.requireNonNull(player, "player"));
        if (entry != null) {
            synchronized (entry) {
                if (!entry.loaded && entry.loading) {
                    entry.ready.add(task);
                    return;
                }
            }
        }
        task.run();
    }

    /** Starts the asynchronous read of a player's file; a second call while one is in flight is a no-op. */
    public void beginLoad(UUID player) {
        Objects.requireNonNull(player, "player");
        Entry entry = players.computeIfAbsent(player, id -> new Entry());
        synchronized (entry) {
            if (entry.loaded || entry.loading) {
                return;
            }
            entry.loading = true;
        }
        Path file = playerFile(player);
        io.execute(() -> {
            Map<String, Object> values;
            try {
                values = StateFiles.read(file);
            } catch (RuntimeException failure) {
                Gloss.logExceptionStack(false, failure, "State file for %s could not be read; starting empty.", player);
                values = new LinkedHashMap<>();
            }
            Map<String, Object> loaded = values;
            regions.run(player, () -> applyLoaded(player, loaded));
        });
    }

    /** Flushes the player's file if dirty and drops it from memory; reads afterwards answer defaults. */
    public void forget(UUID player) {
        Entry entry = players.remove(player);
        if (entry == null) {
            return;
        }
        List<Runnable> ready;
        synchronized (entry) {
            ready = new ArrayList<>(entry.ready);
            entry.ready.clear();
        }
        for (Runnable waiter : ready) {
            waiter.run();
        }
        FileKey key = new FileKey(StateScope.PLAYER, player);
        if (dirty.remove(key)) {
            writeLater(key, entry);
        }
    }

    /** Hands every dirty file to the IO thread; the maps are copied here so the writer never sees a mutation. */
    public void flush() {
        if (dirty.isEmpty()) {
            return;
        }
        List<FileKey> keys = new ArrayList<>(dirty);
        for (FileKey key : keys) {
            if (!dirty.remove(key)) {
                continue;
            }
            Entry entry = entry(key.scope(), key.owner(), false);
            if (entry != null) {
                writeLater(key, entry);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        beginLoad(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        onPlayerQuit(event.getPlayer().getUniqueId());
    }

    /**
     * The store registers before the behaviors lane, so at the same priority its quit handler runs
     * first. A lane that fires quit triggers claims the drop for after them — otherwise a
     * {@code quit} entry's {@code setState} would land in an entry this method had already removed.
     */
    public void deferQuitForget(boolean deferred) {
        this.quitForgetDeferred = deferred;
    }

    void onPlayerQuit(UUID player) {
        if (!quitForgetDeferred) {
            forget(player);
        }
    }

    private void applyLoaded(UUID player, Map<String, Object> loaded) {
        Entry entry = players.get(player);
        if (entry == null) {
            return;
        }
        List<Runnable> pending;
        List<Runnable> ready;
        synchronized (entry) {
            for (Map.Entry<String, Object> value : loaded.entrySet()) {
                if (value.getValue() != null) {
                    entry.values.putIfAbsent(value.getKey(), value.getValue());
                }
            }
            entry.loaded = true;
            entry.loading = false;
            pending = new ArrayList<>(entry.pending);
            entry.pending.clear();
            ready = new ArrayList<>(entry.ready);
            entry.ready.clear();
        }
        for (Runnable write : pending) {
            write.run();
        }
        if (!pending.isEmpty()) {
            dirty.add(new FileKey(StateScope.PLAYER, player));
        }
        for (Runnable waiter : ready) {
            waiter.run();
        }
    }

    private void mutate(StateScope scope, UUID owner, Consumer<Entry> write) {
        Entry entry = entry(scope, owner, true);
        synchronized (entry) {
            if (!entry.loaded && !entry.loading) {
                players.remove(owner, entry);
                Gloss.warnThrottled("state-write-unloaded",
                    "State write for %s was dropped; the player's file is not loaded.", owner);
                return;
            }
            if (!entry.loaded) {
                entry.pending.add(() -> write.accept(entry));
                return;
            }
            write.accept(entry);
        }
        dirty.add(new FileKey(scope, scope == StateScope.GLOBAL ? GLOBAL_OWNER : owner));
    }

    private StateSchema require(StateScope scope, String key) {
        StateSchema schema = declarations.get(key);
        if (schema == null) {
            throw new IllegalArgumentException("state key " + key + " is not declared by any behavior");
        }
        if (schema.scope() != scope) {
            throw new IllegalArgumentException("state key " + key + " is " + schema.scope().key()
                + " scoped, not " + scope.key());
        }
        return schema;
    }

    private Entry entry(StateScope scope, UUID owner, boolean create) {
        return switch (scope) {
            case GLOBAL -> global;
            case WORLD -> {
                UUID world = Objects.requireNonNull(owner, "world");
                yield create ? worlds.computeIfAbsent(world, id -> loadedWorld(id)) : worlds.get(world);
            }
            case PLAYER -> {
                UUID player = Objects.requireNonNull(owner, "player");
                yield create ? players.computeIfAbsent(player, id -> new Entry()) : players.get(player);
            }
        };
    }

    private Entry loadedWorld(UUID world) {
        Entry entry = new Entry();
        entry.values.putAll(StateFiles.read(worldFile(world)));
        entry.loaded = true;
        return entry;
    }

    private void loadWorldFiles() {
        Path folder = root.resolve("worlds");
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID world;
                try {
                    world = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                } catch (IllegalArgumentException notAWorld) {
                    continue;
                }
                worlds.computeIfAbsent(world, this::loadedWorld);
            }
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "World state files could not be listed.");
        }
        for (World world : Bukkit.getWorlds()) {
            worlds.computeIfAbsent(world.getUID(), this::loadedWorld);
        }
    }

    private void writeLater(FileKey key, Entry entry) {
        Map<String, Object> copy;
        synchronized (entry) {
            copy = new LinkedHashMap<>(entry.values);
        }
        Path file = file(key);
        io.execute(() -> {
            try {
                StateFiles.write(file, copy);
            } catch (RuntimeException failure) {
                dirty.add(key);
                Gloss.logExceptionStackThrottled(false, "state-flush", failure,
                    "State file %s could not be written; retrying on the next flush.", file.getFileName());
            }
        });
    }

    private void awaitIo() {
        if (ownedIo == null) {
            return;
        }
        ownedIo.shutdown();
        try {
            if (!ownedIo.awaitTermination(DISABLE_FLUSH_SECONDS, TimeUnit.SECONDS)) {
                Gloss.log(Level.WARNING, "State flush did not finish within %ds on disable.", DISABLE_FLUSH_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatchToPlayer(UUID id, Runnable task) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !FoliaScheduler.runEntity(plugin, player, task)) {
            players.computeIfPresent(id, (key, entry) -> {
                synchronized (entry) {
                    entry.loading = false;
                }
                return entry;
            });
        }
    }

    private Path file(FileKey key) {
        return switch (key.scope()) {
            case GLOBAL -> globalFile();
            case WORLD -> worldFile(key.owner());
            case PLAYER -> playerFile(key.owner());
        };
    }

    private Path globalFile() {
        return root.resolve("global.json");
    }

    private Path worldFile(UUID world) {
        return root.resolve("worlds").resolve(world + ".json");
    }

    private Path playerFile(UUID player) {
        return root.resolve("players").resolve(player + ".json");
    }

    private static final class Entry {
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private final List<Runnable> pending = new ArrayList<>();
        private final List<Runnable> ready = new ArrayList<>();
        private boolean loaded;
        private boolean loading;
    }

    private record FileKey(StateScope scope, UUID owner) {
    }
}
