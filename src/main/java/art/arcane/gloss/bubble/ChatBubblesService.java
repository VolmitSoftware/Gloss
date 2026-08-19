package art.arcane.gloss.bubble;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChatBubblesService implements Listener {
    private static final String SEND_PERMISSION = "gloss.bubbles.send";
    private static final String STATE_FILE_NAME = "bubble-styles.json";
    private static final int STYLE_PERSIST_DELAY_TICKS = 40;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<BubbleStyleDoc> registry;
    private final Map<UUID, SenderState> bubbles = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerStyles = new ConcurrentHashMap<>();
    private final AtomicBoolean persistScheduled = new AtomicBoolean();
    private final File stateFile;

    private volatile Map<String, GlossDocument<BubbleStyleDoc>> stylesSource;
    private volatile Map<String, BubbleStyleDoc> stylesDerived = Map.of();
    private boolean hookRegistered;
    private int driverTaskId = -1;

    public ChatBubblesService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), BubbleStyleDoc.KIND);
        this.defaults = new ShippedDefaults(BubbleStyleDoc.KIND, folder, ShippedDocumentCatalog.BUBBLES.names());
        this.registry = DocumentRegistry.folder(BubbleStyleDoc.KIND, folder, BubbleStyleDoc::parse,
            BubbleStyleDoc::revision);
        this.stateFile = new File(plugin.getDataFolder(), STATE_FILE_NAME);
    }

    public void enable() {
        defaults.extractMissing();
        registry.reload();
        loadPlayerStyles();
        plugin.watchdog().register(BubbleStyleDoc.KIND, registry::poll);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!plugin.cfg().bubbles().enabled()) {
            return;
        }
        registerHook();
        startDriver();
    }

    public void disable() {
        plugin.watchdog().unregister(BubbleStyleDoc.KIND);
        stopDriver();
        HandlerList.unregisterAll(this);
        destroyAll();
        flushPlayerStyles();
        hookRegistered = false;
    }

    public void reload() {
        registry.reload();
        BubbleStyles.clearPatternCache();
        if (!plugin.cfg().bubbles().enabled()) {
            stopDriver();
            destroyAll();
            return;
        }
        if (!hookRegistered) {
            registerHook();
        }
        stopDriver();
        startDriver();
    }

    public int activeCount() {
        int total = 0;
        for (SenderState state : bubbles.values()) {
            total += state.live.size();
        }
        return total;
    }

    public List<String> styles() {
        List<String> ids = new ArrayList<>(registry.ids());
        ids.sort(String::compareTo);
        return ids;
    }

    public BubbleStyleDoc style(String id) {
        GlossDocument<BubbleStyleDoc> document = registry.get(id);
        return document == null ? null : document.value();
    }

    public boolean setPlayerStyle(UUID uuid, String styleId) {
        if (uuid == null) {
            return false;
        }
        if (styleId == null) {
            playerStyles.remove(uuid);
            persistPlayerStyles();
            return true;
        }
        if (registry.get(styleId) == null) {
            return false;
        }
        playerStyles.put(uuid, styleId);
        persistPlayerStyles();
        return true;
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        bubbles.remove(event.getPlayer().getUniqueId());
    }

    private void registerHook() {
        plugin.chat().addChatHook(this::onChat);
        hookRegistered = true;
    }

    private void startDriver() {
        if (driverTaskId != -1) {
            return;
        }
        driverTaskId = plugin.scheduler().sr(this::drive,
            Math.max(1, plugin.cfg().holograms().temporaryUpdateIntervalTicks()));
    }

    private void stopDriver() {
        if (driverTaskId == -1) {
            return;
        }
        plugin.scheduler().csr(driverTaskId);
        driverTaskId = -1;
    }

    private void onChat(Player sender, String message) {
        if (!plugin.cfg().bubbles().enabled()) {
            return;
        }

        BubbleStyleDoc style = resolveStyle(sender);
        List<String> lines = BubbleLines.split(message, style.wordWrapChars());
        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            int index = lineNumber;
            FoliaScheduler.runEntity(plugin, sender, () -> spawn(sender, style, line, index),
                (long) style.lineStaggerTicks() * lineNumber);
        }
    }

    private BubbleStyleDoc resolveStyle(Player sender) {
        Map<String, BubbleStyleDoc> snapshot = stylesSnapshot();
        String chosen = playerStyles.get(sender.getUniqueId());
        String primaryGroup = plugin.groups().cachedPrimaryGroupFor(sender).orElse(null);
        String resolved = BubbleStyles.resolveStyleId(chosen, sender::hasPermission, snapshot,
            sender.getWorld().getName(), primaryGroup);
        BubbleStyleDoc style = resolved == null ? null : snapshot.get(resolved);
        return style == null ? BubbleStyleDoc.DEFAULTS : style;
    }

    private Map<String, BubbleStyleDoc> stylesSnapshot() {
        Map<String, GlossDocument<BubbleStyleDoc>> documents = registry.snapshot();
        if (documents == stylesSource) {
            return stylesDerived;
        }

        Map<String, BubbleStyleDoc> derived = new HashMap<>(documents.size());
        for (Map.Entry<String, GlossDocument<BubbleStyleDoc>> entry : documents.entrySet()) {
            derived.put(entry.getKey(), entry.getValue().value());
        }
        stylesDerived = derived;
        stylesSource = documents;
        return derived;
    }

    private void spawn(Player sender, BubbleStyleDoc style, String line, int lineNumber) {
        if (!plugin.cfg().bubbles().enabled() || !sender.isOnline()) {
            return;
        }
        if (plugin.cfg().bubbles().blacklistWorlds().contains(sender.getWorld().getName())) {
            return;
        }
        if (!sender.hasPermission(SEND_PERMISSION)) {
            return;
        }

        Location eye = sender.getEyeLocation();
        EyePoint eyePoint = EyePoint.of(eye);
        Location captured = eye.add(style.offset().getX(), style.offset().getY(), style.offset().getZ());
        String id = "chat-" + sender.getUniqueId() + "-" + M.ms() + "-" + lineNumber;
        TemporaryHologram hologram = plugin.holograms().createTemporary(id, captured.clone(), style.maxAliveMs());
        GlossTelemetry.countBubbleSpawn();
        hologram.addLine(style.prefix() + line);
        if (style.hideOwn()) {
            hologram.viewers().add(sender.getUniqueId());
        }

        BubbleRecord record = new BubbleRecord(hologram, captured, style.flyAway(), style.followPlayer(),
            M.ms() + style.maxAliveMs());
        SenderState state = bubbles.compute(sender.getUniqueId(), (uuid, existing) -> {
            SenderState target = existing == null ? new SenderState() : existing;
            target.lastEye = eyePoint;
            target.add(record);
            return target;
        });
        hologram.bindPosition(() -> bubblePosition(state, record));
    }

    private Location bubblePosition(SenderState state, BubbleRecord record) {
        double lift = BubbleStackMath.offsetY(plugin.holograms().stackSpread(), record.lineIndex, state.live.size(),
            record.hologram.remainingMs(), record.flyAway);
        EyePoint eye = record.followPlayer ? state.lastEye : null;
        if (eye == null) {
            return record.captured.clone().add(0.0D, lift, 0.0D);
        }
        return eye.toLocation(lift);
    }

    private void drive() {
        long now = M.ms();
        for (Map.Entry<UUID, SenderState> entry : bubbles.entrySet()) {
            SenderState state = entry.getValue();
            sweepExpired(entry.getKey(), state, now);
            if (state.followCount.get() <= 0) {
                continue;
            }
            Player sender = plugin.getServer().getPlayer(entry.getKey());
            if (sender == null) {
                continue;
            }
            FoliaScheduler.runEntity(plugin, sender, () -> state.lastEye = EyePoint.of(sender.getEyeLocation()));
        }
    }

    private void sweepExpired(UUID senderId, SenderState state, long now) {
        for (BubbleRecord record : state.live) {
            if (record.expiresAtMs <= now) {
                untrack(senderId, record);
            }
        }
    }

    private void untrack(UUID senderId, BubbleRecord record) {
        bubbles.computeIfPresent(senderId, (uuid, state) -> {
            state.remove(record);
            return state.live.isEmpty() ? null : state;
        });
    }

    private void destroyAll() {
        int failures = 0;
        for (SenderState state : bubbles.values()) {
            for (BubbleRecord record : state.live) {
                try {
                    record.hologram.destroy();
                } catch (Throwable failure) {
                    failures++;
                }
            }
            state.live.clear();
        }
        bubbles.clear();
        if (failures > 0) {
            Gloss.warn("Failed to destroy " + failures + " chat bubbles on shutdown.");
        }
    }

    private void loadPlayerStyles() {
        playerStyles.clear();
        if (!stateFile.exists()) {
            return;
        }
        try {
            String json = Files.readString(stateFile.toPath());
            Map<String, String> raw = new Gson().fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());
            if (raw == null) {
                return;
            }
            raw.forEach((key, value) -> {
                if (value == null || value.isBlank()) {
                    return;
                }
                try {
                    playerStyles.put(UUID.fromString(key), value);
                } catch (IllegalArgumentException ignored) {
                }
            });
        } catch (Exception failure) {
            Gloss.logExceptionStack(false, failure, "Failed to load bubble styles.");
        }
    }

    private void persistPlayerStyles() {
        if (!persistScheduled.compareAndSet(false, true)) {
            return;
        }
        plugin.scheduler().a(() -> {
            persistScheduled.set(false);
            writePlayerStyles();
        }, STYLE_PERSIST_DELAY_TICKS);
    }

    private void flushPlayerStyles() {
        if (!persistScheduled.compareAndSet(true, false)) {
            return;
        }
        writePlayerStyles();
    }

    private synchronized void writePlayerStyles() {
        try {
            Map<String, String> raw = new TreeMap<>();
            playerStyles.forEach((key, value) -> raw.put(key.toString(), value));
            File parent = stateFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.writeString(stateFile.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(raw));
        } catch (Exception failure) {
            Gloss.logExceptionStack(false, failure, "Failed to save bubble styles.");
        }
    }

    record EyePoint(World world, double x, double y, double z, float yaw, float pitch) {
        static EyePoint of(Location location) {
            return new EyePoint(location.getWorld(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        }

        Location toLocation(double lift) {
            return new Location(world, x, y + lift, z, yaw, pitch);
        }
    }

    static final class SenderState {
        final List<BubbleRecord> live = new CopyOnWriteArrayList<>();
        final AtomicInteger followCount = new AtomicInteger();
        volatile EyePoint lastEye;

        void add(BubbleRecord record) {
            record.lineIndex = live.size();
            live.add(record);
            if (record.followPlayer) {
                followCount.incrementAndGet();
            }
        }

        void remove(BubbleRecord record) {
            int index = live.indexOf(record);
            if (index < 0) {
                return;
            }
            live.remove(index);
            for (int position = index; position < live.size(); position++) {
                live.get(position).lineIndex = position;
            }
            record.lineIndex = 0;
            if (record.followPlayer) {
                followCount.decrementAndGet();
            }
        }
    }

    static final class BubbleRecord {
        final TemporaryHologram hologram;
        final Location captured;
        final boolean flyAway;
        final boolean followPlayer;
        final long expiresAtMs;
        volatile int lineIndex;

        BubbleRecord(TemporaryHologram hologram, Location captured, boolean flyAway, boolean followPlayer,
                     long expiresAtMs) {
            this.hologram = hologram;
            this.captured = captured;
            this.flyAway = flyAway;
            this.followPlayer = followPlayer;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
