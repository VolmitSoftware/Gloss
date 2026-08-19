package art.arcane.gloss.bubble;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.util.Ticks;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

public final class ChatBubblesService implements Listener {
    private static final String SEND_PERMISSION = "gloss.bubbles.send";
    private static final String STATE_FILE_NAME = "bubble-styles.json";
    private static final int EYE_REFRESH_INTERVAL_TICKS = 1;

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<BubbleStyleDoc> registry;
    private final Map<UUID, SenderState> bubbles = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerStyles = new ConcurrentHashMap<>();
    private final File stateFile;

    private boolean hookRegistered;
    private int eyeTaskId = -1;

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
        startEyeTask();
    }

    public void disable() {
        plugin.watchdog().unregister(BubbleStyleDoc.KIND);
        stopEyeTask();
        HandlerList.unregisterAll(this);
        destroyAll();
        hookRegistered = false;
    }

    public void reload() {
        registry.reload();
        if (!plugin.cfg().bubbles().enabled()) {
            stopEyeTask();
            destroyAll();
            return;
        }
        if (!hookRegistered) {
            registerHook();
        }
        startEyeTask();
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bubbles.remove(event.getPlayer().getUniqueId());
    }

    private void registerHook() {
        plugin.chat().addChatHook(this::onChat);
        hookRegistered = true;
    }

    private void startEyeTask() {
        if (eyeTaskId != -1) {
            return;
        }
        eyeTaskId = plugin.scheduler().sr(this::refreshEyes, EYE_REFRESH_INTERVAL_TICKS);
    }

    private void stopEyeTask() {
        if (eyeTaskId == -1) {
            return;
        }
        plugin.scheduler().csr(eyeTaskId);
        eyeTaskId = -1;
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
        String primaryGroup = plugin.groups().primaryGroupFor(sender).orElse(null);
        String resolved = BubbleStyles.resolveStyleId(chosen, sender::hasPermission, snapshot,
            sender.getWorld().getName(), primaryGroup);
        BubbleStyleDoc style = resolved == null ? null : snapshot.get(resolved);
        return style == null ? BubbleStyleDoc.DEFAULTS : style;
    }

    private Map<String, BubbleStyleDoc> stylesSnapshot() {
        Map<String, GlossDocument<BubbleStyleDoc>> documents = registry.snapshot();
        Map<String, BubbleStyleDoc> snapshot = new HashMap<>(documents.size());
        for (Map.Entry<String, GlossDocument<BubbleStyleDoc>> entry : documents.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().value());
        }
        return snapshot;
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

        Location captured = sender.getEyeLocation()
            .add(style.offset().getX(), style.offset().getY(), style.offset().getZ());
        String id = "chat-" + sender.getUniqueId() + "-" + M.ms() + "-" + lineNumber;
        TemporaryHologram hologram = plugin.holograms().createTemporary(id, captured.clone(), style.maxAliveMs());
        GlossTelemetry.countBubbleSpawn();
        hologram.addLine(style.prefix() + line);
        if (style.hideOwn()) {
            hologram.viewers().add(sender.getUniqueId());
        }

        UUID senderId = sender.getUniqueId();
        SenderState state = bubbles.computeIfAbsent(senderId, ignored -> new SenderState());
        state.lastEye = sender.getEyeLocation();
        BubbleRecord record = new BubbleRecord(hologram, captured, style.flyAway(), style.followPlayer());
        state.live.add(record);
        hologram.bindPosition(() -> bubblePosition(state, record));
        plugin.scheduler().a(() -> untrack(senderId, record), Ticks.fromMs(style.maxAliveMs()));
    }

    private Location bubblePosition(SenderState state, BubbleRecord record) {
        double spread = plugin.holograms().stackSpread();
        int lineIndex = Math.max(state.live.indexOf(record), 0);
        int liveCount = state.live.size();
        double lift = BubbleStackMath.offsetY(spread, lineIndex, liveCount, record.hologram.remainingMs(),
            record.flyAway);
        Location base = record.followPlayer ? state.lastEye : record.captured;
        if (base == null) {
            base = record.captured;
        }
        return base.clone().add(0.0D, lift, 0.0D);
    }

    private void refreshEyes() {
        for (Map.Entry<UUID, SenderState> entry : bubbles.entrySet()) {
            SenderState state = entry.getValue();
            if (state.live.isEmpty() || !anyFollows(state)) {
                continue;
            }
            Player sender = plugin.getServer().getPlayer(entry.getKey());
            if (sender == null) {
                continue;
            }
            FoliaScheduler.runEntity(plugin, sender, () -> state.lastEye = sender.getEyeLocation());
        }
    }

    private boolean anyFollows(SenderState state) {
        for (BubbleRecord record : state.live) {
            if (record.followPlayer) {
                return true;
            }
        }
        return false;
    }

    private void untrack(UUID senderId, BubbleRecord record) {
        SenderState state = bubbles.get(senderId);
        if (state == null) {
            return;
        }
        state.live.remove(record);
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

    private synchronized void persistPlayerStyles() {
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

    private static final class SenderState {
        private final List<BubbleRecord> live = new CopyOnWriteArrayList<>();
        private volatile Location lastEye;
    }

    private static final class BubbleRecord {
        private final TemporaryHologram hologram;
        private final Location captured;
        private final boolean flyAway;
        private final boolean followPlayer;

        private BubbleRecord(TemporaryHologram hologram, Location captured, boolean flyAway, boolean followPlayer) {
            this.hologram = hologram;
            this.captured = captured;
            this.flyAway = flyAway;
            this.followPlayer = followPlayer;
        }
    }
}
