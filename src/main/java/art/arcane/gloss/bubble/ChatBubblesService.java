package art.arcane.gloss.bubble;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.bubble.BubbleMotionPlan.BubbleMotionContext;
import art.arcane.gloss.bubble.BubbleMotionPlan.BubbleMotionSample;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.text.TextPipeline;
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
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public final class ChatBubblesService implements Listener {
    private static final String SEND_PERMISSION = "gloss.bubbles.send";
    private static final String STATE_FILE_NAME = "bubble-styles.json";
    private static final int STYLE_PERSIST_DELAY_TICKS = 40;
    static final int EXPIRY_SWEEP_INTERVAL_TICKS = 20;
    private static final int MAX_BUBBLES_PER_SENDER = 4;
    private static final int MAX_ACTIVE_BUBBLES = 2048;
    private static final byte[] LEGACY_DEFAULT = ("{\n"
        + "  \"schemaVersion\": 1,\n"
        + "  \"revision\": 1,\n"
        + "  \"prefix\": \"&7\",\n"
        + "  \"offset\": [0.0, 1.0, 0.0],\n"
        + "  \"wordWrapChars\": 32,\n"
        + "  \"lineStaggerTicks\": 5,\n"
        + "  \"maxAliveMs\": 5000,\n"
        + "  \"flyAway\": true,\n"
        + "  \"followPlayer\": true,\n"
        + "  \"hideOwn\": true\n"
        + "}\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS_SCHEMA_TWO_DEFAULT = ("{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"revision\": 1,\n"
        + "  \"prefix\": \"&7\",\n"
        + "  \"offset\": [0.0, 1.0, 0.0],\n"
        + "  \"wordWrapChars\": 32,\n"
        + "  \"maxAliveMs\": 5000,\n"
        + "  \"followPlayer\": true,\n"
        + "  \"hideOwn\": true,\n"
        + "  \"motion\": {\n"
        + "    \"translation\": {\n"
        + "      \"x\": \"0\",\n"
        + "      \"y\": \"10 * pow(clamp((ageMs - lifetimeMs + 2000) / 2000, 0, 1), 16)\",\n"
        + "      \"z\": \"0\"\n"
        + "    },\n"
        + "    \"scale\": {\n"
        + "      \"x\": \"1\",\n"
        + "      \"y\": \"1\",\n"
        + "      \"z\": \"1\"\n"
        + "    },\n"
        + "    \"rotation\": {\n"
        + "      \"x\": \"0\",\n"
        + "      \"y\": \"0\",\n"
        + "      \"z\": \"0\"\n"
        + "    },\n"
        + "    \"opacity\": \"1\"\n"
        + "  },\n"
        + "  \"shimmer\": {\n"
        + "    \"spawn\": true,\n"
        + "    \"flyAway\": true,\n"
        + "    \"color\": \"#ffffff\",\n"
        + "    \"width\": 3,\n"
        + "    \"durationMs\": 700,\n"
        + "    \"spawnDelayMs\": 0,\n"
        + "    \"flyAwayLeadMs\": 700\n"
        + "  }\n"
        + "}\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS_HEIGHT_DEFAULT = new String(
        PREVIOUS_SCHEMA_TWO_DEFAULT, StandardCharsets.UTF_8)
        .replace("\"spawnDelayMs\": 0", "\"spawnDelayMs\": 400")
        .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS_TWO_TONE_DEFAULT = ("{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"revision\": 1,\n"
        + "  \"prefix\": \"&7\",\n"
        + "  \"offset\": [0.0, 1.0, 0.0],\n"
        + "  \"wordWrapChars\": 32,\n"
        + "  \"maxAliveMs\": 5000,\n"
        + "  \"followPlayer\": true,\n"
        + "  \"hideOwn\": true,\n"
        + "  \"motion\": {\n"
        + "    \"translation\": {\n"
        + "      \"x\": \"0\",\n"
        + "      \"y\": \"10 * pow(clamp((ageMs - lifetimeMs + 2000) / 2000, 0, 1), 16)\",\n"
        + "      \"z\": \"0\"\n"
        + "    },\n"
        + "    \"scale\": {\n"
        + "      \"x\": \"1\",\n"
        + "      \"y\": \"1\",\n"
        + "      \"z\": \"1\"\n"
        + "    },\n"
        + "    \"rotation\": {\n"
        + "      \"x\": \"0\",\n"
        + "      \"y\": \"0\",\n"
        + "      \"z\": \"0\"\n"
        + "    },\n"
        + "    \"opacity\": \"1\"\n"
        + "  },\n"
        + "  \"shimmer\": {\n"
        + "    \"spawn\": true,\n"
        + "    \"flyAway\": false,\n"
        + "    \"color\": \"#ffffff\",\n"
        + "    \"edgeColor\": \"#aaaaaa\",\n"
        + "    \"width\": 3,\n"
        + "    \"durationMs\": 4233,\n"
        + "    \"spawnDelayMs\": 0,\n"
        + "    \"flyAwayLeadMs\": 700\n"
        + "  }\n"
        + "}\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS_LEGACY_CYCLE_DEFAULT = ("{\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"revision\": 1,\n"
        + "  \"prefix\": \"&7\",\n"
        + "  \"offset\": [0.0, 1.0, 0.0],\n"
        + "  \"wordWrapChars\": 32,\n"
        + "  \"maxAliveMs\": 5000,\n"
        + "  \"followPlayer\": true,\n"
        + "  \"hideOwn\": true,\n"
        + "  \"motion\": {\n"
        + "    \"translation\": {\n"
        + "      \"x\": \"0\",\n"
        + "      \"y\": \"10 * pow(clamp((ageMs - lifetimeMs + 2000) / 2000, 0, 1), 16)\",\n"
        + "      \"z\": \"0\"\n"
        + "    },\n"
        + "    \"scale\": {\n"
        + "      \"x\": \"1\",\n"
        + "      \"y\": \"1\",\n"
        + "      \"z\": \"1\"\n"
        + "    },\n"
        + "    \"rotation\": {\n"
        + "      \"x\": \"0\",\n"
        + "      \"y\": \"0\",\n"
        + "      \"z\": \"0\"\n"
        + "    },\n"
        + "    \"opacity\": \"1\"\n"
        + "  },\n"
        + "  \"shimmer\": {\n"
        + "    \"spawn\": true,\n"
        + "    \"flyAway\": false,\n"
        + "    \"color\": \"#ffffff\",\n"
        + "    \"width\": 3,\n"
        + "    \"durationMs\": 4233,\n"
        + "    \"spawnDelayMs\": 0,\n"
        + "    \"flyAwayLeadMs\": 700\n"
        + "  }\n"
        + "}\n").getBytes(StandardCharsets.UTF_8);

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<BubbleStyleDoc> registry;
    private final Object bubbleLifecycleLock = new Object();
    private final ConcurrentMap<UUID, SenderState> bubbles = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerStyles = new ConcurrentHashMap<>();
    private final AtomicBoolean persistScheduled = new AtomicBoolean();
    private final AtomicLong bubbleSequence = new AtomicLong();
    private final AtomicInteger activeBubbles = new AtomicInteger();
    private final File stateFile;

    private volatile Map<String, GlossDocument<BubbleStyleDoc>> stylesSource;
    private volatile StyleSnapshot stylesDerived = new StyleSnapshot(Map.of(), Map.of());
    private boolean acceptingBubbles;
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

    /**
     * The shipped styles are extracted only while bubbles are on, so a server that leaves the
     * feature off never grows a {@code bubbles/} folder. Everything else still runs, so turning the
     * feature on through {@link #reload()} picks the styles up without a restart.
     */
    public void enable() {
        boolean enabled = plugin.cfg().bubbles().enabled();
        if (enabled) {
            extractDefaults();
        }
        registry.reload();
        loadPlayerStyles();
        plugin.watchdog().register(BubbleStyleDoc.KIND, this::pollRegistry);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!enabled) {
            synchronized (bubbleLifecycleLock) {
                acceptingBubbles = false;
            }
            return;
        }
        synchronized (bubbleLifecycleLock) {
            acceptingBubbles = true;
        }
        registerHook();
        startDriver();
    }

    static boolean upgradeLegacyDefault(ShippedDefaults defaults) {
        return defaults.replaceIfExact("default", LEGACY_DEFAULT)
            || defaults.replaceIfExact("default", PREVIOUS_SCHEMA_TWO_DEFAULT)
            || defaults.replaceIfExact("default", PREVIOUS_HEIGHT_DEFAULT)
            || defaults.replaceIfExact("default", PREVIOUS_TWO_TONE_DEFAULT)
            || defaults.replaceIfExact("default", PREVIOUS_LEGACY_CYCLE_DEFAULT);
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (!delta.isEmpty()) {
            registry.acknowledge(delta);
        }
    }

    public void disable() {
        plugin.watchdog().unregister(BubbleStyleDoc.KIND);
        registry.close();
        stopDriver();
        HandlerList.unregisterAll(this);
        destroyAll();
        flushPlayerStyles();
        hookRegistered = false;
    }

    public void reload() {
        boolean enabled = plugin.cfg().bubbles().enabled();
        if (enabled) {
            extractDefaults();
        }
        registry.reload();
        BubbleStyles.clearPatternCache();
        if (!enabled) {
            stopDriver();
            destroyAll();
            return;
        }
        synchronized (bubbleLifecycleLock) {
            acceptingBubbles = true;
        }
        if (!hookRegistered) {
            registerHook();
        }
        stopDriver();
        startDriver();
    }

    private void extractDefaults() {
        defaults.extractMissing();
        if (upgradeLegacyDefault(defaults)) {
            Gloss.info("Upgraded the untouched bubble default to the current shipped style.");
        }
    }

    public int activeCount() {
        int total = 0;
        for (SenderState state : bubbles.values()) {
            total += state.size();
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
        List<BubbleRecord> removed = drainSender(bubbles, event.getPlayer().getUniqueId());
        for (BubbleRecord record : removed) {
            retire(record);
        }
    }

    private void registerHook() {
        plugin.chat().addChatHook(this::onChat);
        hookRegistered = true;
    }

    private void startDriver() {
        if (driverTaskId != -1) {
            return;
        }
        driverTaskId = plugin.scheduler().sr(this::drive, EXPIRY_SWEEP_INTERVAL_TICKS);
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
        String capturedMessage = message == null ? "" : message;
        FoliaScheduler.runEntity(plugin, sender, () -> prepareAndSpawn(sender, capturedMessage));
    }

    private void prepareAndSpawn(Player sender, String message) {
        if (!plugin.cfg().bubbles().enabled() || !sender.isOnline()) {
            return;
        }
        if (plugin.cfg().bubbles().blacklistWorlds().contains(sender.getWorld().getName())) {
            return;
        }
        if (!sender.hasPermission(SEND_PERMISSION)) {
            return;
        }

        ResolvedStyle resolved = resolveStyle(sender);
        BubbleStyleDoc style = resolved.document();
        List<String> lines = renderTextBlock(style.prefix(), message, style.wordWrapChars(),
            prefix -> plugin.text().render(sender, prefix));
        if (!lines.isEmpty()) {
            spawn(sender, resolved, message, lines);
        }
    }

    private ResolvedStyle resolveStyle(Player sender) {
        StyleSnapshot snapshot = stylesSnapshot();
        String chosen = playerStyles.get(sender.getUniqueId());
        String primaryGroup = plugin.groups().primaryGroupFor(sender).orElse(null);
        String resolved = BubbleStyles.resolveStyleId(chosen, sender::hasPermission, snapshot.documents(),
            sender.getWorld().getName(), primaryGroup);
        ResolvedStyle style = resolved == null ? null : snapshot.resolved().get(resolved);
        return style == null ? ResolvedStyle.DEFAULT : style;
    }

    private StyleSnapshot stylesSnapshot() {
        Map<String, GlossDocument<BubbleStyleDoc>> documents = registry.snapshot();
        if (documents == stylesSource) {
            return stylesDerived;
        }

        Map<String, BubbleStyleDoc> derived = new HashMap<>(documents.size());
        Map<String, ResolvedStyle> resolved = new HashMap<>(documents.size());
        for (Map.Entry<String, GlossDocument<BubbleStyleDoc>> entry : documents.entrySet()) {
            BubbleStyleDoc document = entry.getValue().value();
            derived.put(entry.getKey(), document);
            resolved.put(entry.getKey(), new ResolvedStyle(document, BubbleMotionPlan.compile(document.motion(),
                "bubble style '" + entry.getKey() + "' revision " + document.revision()),
                BubbleShimmerPlan.compile(document.shimmer())));
        }
        StyleSnapshot snapshot = new StyleSnapshot(Map.copyOf(derived), Map.copyOf(resolved));
        stylesDerived = snapshot;
        stylesSource = documents;
        return snapshot;
    }

    private void spawn(Player sender, ResolvedStyle resolved, String message, List<String> lines) {
        BubbleStyleDoc style = resolved.document();
        Location eye = sender.getEyeLocation();
        EyePoint eyePoint = EyePoint.of(eye);
        Vector offset = style.offset();
        Location captured = eye.clone().add(offset.getX(), offset.getY(), offset.getZ());
        UUID senderId = sender.getUniqueId();
        synchronized (bubbleLifecycleLock) {
            if (!acceptingBubbles) {
                return;
            }
        }
        if (!reserveBubble()) {
            return;
        }

        long startedAtMs = M.ms();
        long sequence = bubbleSequence.incrementAndGet();
        String id = "chat-" + senderId + "-" + startedAtMs + "-" + sequence;
        TemporaryHologram hologram = null;
        BubbleRecord record = null;
        try {
            hologram = plugin.holograms().createTemporary(id, captured.clone(), style.maxAliveMs());
            if (style.hideOwn()) {
                hologram.viewers().add(senderId);
            }

            record = new BubbleRecord(hologram, captured, offset, resolved.motion(), resolved.shimmer(),
                style.followPlayer(), startedAtMs, style.maxAliveMs(), startedAtMs + style.maxAliveMs(), lines.size(),
                seed(senderId, startedAtMs, sequence), style.prefix(), message, style.wordWrapChars(), lines);
            publishInitialText(record, startedAtMs);
            SenderPublication publication;
            synchronized (bubbleLifecycleLock) {
                publication = acceptingBubbles
                    ? publishBubble(bubbles, senderId, eyePoint, record, MAX_BUBBLES_PER_SENDER)
                    : null;
            }
            if (publication == null) {
                retire(record);
                return;
            }
            if (publication.retired() != null) {
                retire(publication.retired());
            }
            SenderState state = publication.state();
            BubbleRecord boundRecord = record;
            hologram.bindPosition(sender, () -> bubblePosition(sender, state, boundRecord));
            hologram.bindPresentation(sender, () -> bubblePresentation(state, boundRecord));
        } catch (RuntimeException | Error failure) {
            if (record != null) {
                try {
                    removeBubble(bubbles, senderId, record);
                    retire(record);
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            } else {
                releaseBubble();
                if (hologram != null) {
                    try {
                        hologram.destroy();
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            throw failure;
        }
        GlossTelemetry.countBubbleSpawn();
    }

    private Location bubblePosition(Player sender, SenderState state, BubbleRecord record) {
        EyePoint eye = record.followPlayer ? EyePoint.of(sender.getEyeLocation()) : null;
        refreshOwnerState(state, record, eye, () -> refreshText(sender, record));
        BubbleFrame frame = sampleFrame(state, record);
        record.lastFrame = frame;
        EyePoint currentEye = record.followPlayer ? state.lastEye : null;
        Location base = currentEye == null ? record.captured.clone() : currentEye.toLocation(record.offset);
        BubbleMotionSample motion = frame.motion();
        return base.add(motion.translationX(), frame.stackY() + motion.translationY(), motion.translationZ());
    }

    private HologramPresentation bubblePresentation(SenderState state, BubbleRecord record) {
        BubbleFrame frame = record.lastFrame;
        if (frame == null) {
            frame = sampleFrame(state, record);
            record.lastFrame = frame;
        }
        return frame.motion().presentation();
    }

    private BubbleFrame sampleFrame(SenderState state, BubbleRecord record) {
        int stackedLineCount = state.stackedLineCount(record);
        double stackY = BubbleStackMath.offsetY(plugin.holograms().stackSpread(), stackedLineCount);
        long remainingMs = Math.max(0L, record.hologram.remainingMs());
        double ageMs = Math.max(0.0D, record.durationMs - remainingMs);
        double t = Math.max(0.0D, Math.min(1.0D, ageMs / record.durationMs));
        BubbleMotionContext context = new BubbleMotionContext(t, ageMs, record.durationMs, record.lineIndex,
            state.live.size(), record.lineCount, stackY, record.seed);
        return new BubbleFrame(stackY, record.motion.sample(context));
    }

    private static double seed(UUID senderId, long startedAtMs, long sequence) {
        long mixed = senderId.getMostSignificantBits() ^ Long.rotateLeft(senderId.getLeastSignificantBits(), 17)
            ^ startedAtMs ^ Long.rotateLeft(sequence, 31);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53l;
        mixed ^= mixed >>> 33;
        return (mixed >>> 11) * 0x1.0p-53;
    }

    static List<String> renderTextBlock(String prefix, String message, int wrapChars, UnaryOperator<String> renderer) {
        return BubbleTextBlock.wrap(renderer.apply(prefix), message, wrapChars);
    }

    private void drive() {
        long now = M.ms();
        for (Map.Entry<UUID, SenderState> entry : bubbles.entrySet()) {
            sweepExpired(entry.getKey(), entry.getValue(), now);
        }
    }

    static void refreshOwnerState(SenderState state, BubbleRecord record,
                                  EyePoint eyePoint, Runnable dynamicPrefixRefresh) {
        if (record.followPlayer) {
            state.lastEye = eyePoint;
        }
        if (record.dynamicPrefix) {
            dynamicPrefixRefresh.run();
        }
    }

    private void refreshText(Player sender, BubbleRecord record) {
        if (!record.dynamicPrefix) {
            return;
        }
        List<String> lines = renderTextBlock(record.prefix, record.message, record.wrapChars,
            prefix -> plugin.text().render(sender, prefix));
        if (lines.isEmpty() || lines.equals(record.renderedLines)) {
            return;
        }
        record.renderedLines = List.copyOf(lines);
        record.lineCount = lines.size();
        record.hologram.setRenderedLines(record.frameAt(M.ms()));
    }

    static void publishInitialText(BubbleRecord record, long nowMs) {
        record.hologram.setRenderedLines(record.frameAt(nowMs));
        record.hologram.bindRenderedFrames(record::frameAt);
    }

    private void sweepExpired(UUID senderId, SenderState state, long now) {
        for (BubbleRecord record : state.live) {
            if (record.expiresAtMs <= now) {
                untrack(senderId, record);
            }
        }
    }

    private void untrack(UUID senderId, BubbleRecord record) {
        if (removeBubble(bubbles, senderId, record)) {
            retire(record);
        }
    }

    private boolean reserveBubble() {
        int active = activeBubbles.get();
        while (active < MAX_ACTIVE_BUBBLES) {
            if (activeBubbles.compareAndSet(active, active + 1)) {
                return true;
            }
            active = activeBubbles.get();
        }
        return false;
    }

    private void retire(BubbleRecord record) {
        if (!record.claimRetirement()) {
            return;
        }
        releaseBubble();
        if (record.hologram != null) {
            record.hologram.destroy();
        }
    }

    private void releaseBubble() {
        activeBubbles.updateAndGet(active -> Math.max(0, active - 1));
    }

    private void destroyAll() {
        List<BubbleRecord> removed = new ArrayList<>();
        synchronized (bubbleLifecycleLock) {
            acceptingBubbles = false;
            for (UUID senderId : List.copyOf(bubbles.keySet())) {
                removed.addAll(drainSender(bubbles, senderId));
            }
            bubbles.clear();
        }
        int failures = 0;
        for (BubbleRecord record : removed) {
            try {
                retire(record);
            } catch (Throwable failure) {
                failures++;
                Gloss.logExceptionStack(false, failure,
                    "Failed to destroy a chat bubble during shutdown.");
            }
        }
        if (failures > 0) {
            Gloss.warn("Failed to destroy " + failures + " chat bubbles on shutdown.");
        }
    }

    static SenderPublication publishBubble(ConcurrentMap<UUID, SenderState> states, UUID senderId,
                                             EyePoint eyePoint, BubbleRecord record, int limit) {
        AtomicReference<SenderPublication> result = new AtomicReference<>();
        states.compute(senderId, (ignored, existing) -> {
            SenderState state = existing == null ? new SenderState() : existing;
            BubbleRecord retired = state.addAtLimit(record, limit, eyePoint);
            result.set(new SenderPublication(state, retired));
            return state;
        });
        return result.get();
    }

    static boolean removeBubble(ConcurrentMap<UUID, SenderState> states, UUID senderId, BubbleRecord record) {
        AtomicBoolean removed = new AtomicBoolean();
        states.computeIfPresent(senderId, (ignored, state) -> {
            removed.set(state.remove(record));
            return state.isEmpty() ? null : state;
        });
        return removed.get();
    }

    static List<BubbleRecord> drainSender(ConcurrentMap<UUID, SenderState> states, UUID senderId) {
        AtomicReference<List<BubbleRecord>> removed = new AtomicReference<>(List.of());
        states.computeIfPresent(senderId, (ignored, state) -> {
            removed.set(state.drain());
            return null;
        });
        return removed.get();
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

        Location toLocation(Vector offset) {
            return new Location(world, x + offset.getX(), y + offset.getY(), z + offset.getZ(), yaw, pitch);
        }
    }

    static final class SenderState {
        final List<BubbleRecord> live = new CopyOnWriteArrayList<>();
        volatile EyePoint lastEye;

        synchronized void add(BubbleRecord record) {
            record.lineIndex = live.size();
            live.add(record);
        }

        synchronized boolean remove(BubbleRecord record) {
            int index = live.indexOf(record);
            if (index < 0) {
                return false;
            }
            live.remove(index);
            for (int position = index; position < live.size(); position++) {
                live.get(position).lineIndex = position;
            }
            record.lineIndex = 0;
            return true;
        }

        synchronized BubbleRecord addAtLimit(BubbleRecord record, int limit, EyePoint eyePoint) {
            BubbleRecord retired = removeOldestAtLimit(limit);
            lastEye = eyePoint;
            add(record);
            return retired;
        }

        synchronized BubbleRecord removeOldestAtLimit(int limit) {
            if (live.size() < limit) {
                return null;
            }
            BubbleRecord oldest = live.getFirst();
            remove(oldest);
            return oldest;
        }

        synchronized List<BubbleRecord> drain() {
            List<BubbleRecord> snapshot = List.copyOf(live);
            live.clear();
            for (BubbleRecord record : snapshot) {
                record.lineIndex = 0;
            }
            return snapshot;
        }

        synchronized boolean isEmpty() {
            return live.isEmpty();
        }

        synchronized int size() {
            return live.size();
        }

        synchronized int stackedLineCount(BubbleRecord record) {
            int index = live.indexOf(record);
            if (index < 0) {
                return 0;
            }
            int count = 0;
            for (int position = index + 1; position < live.size(); position++) {
                count += live.get(position).lineCount;
            }
            return count;
        }
    }

    static final class BubbleRecord {
        final TemporaryHologram hologram;
        final Location captured;
        final Vector offset;
        final BubbleMotionPlan motion;
        final BubbleShimmerPlan shimmer;
        final boolean followPlayer;
        final long startedAtMs;
        final long durationMs;
        final long expiresAtMs;
        final double seed;
        final String prefix;
        final String message;
        final int wrapChars;
        final boolean dynamicPrefix;
        private final AtomicBoolean retired = new AtomicBoolean();
        volatile int lineIndex;
        volatile int lineCount;
        volatile List<String> renderedLines;
        volatile List<String> shimmerBase;
        volatile int shimmerVisibleCount;
        volatile ShimmerFrame shimmerFrame;
        volatile BubbleFrame lastFrame;

        BubbleRecord(TemporaryHologram hologram, Location captured, Vector offset, BubbleMotionPlan motion,
                     BubbleShimmerPlan shimmer, boolean followPlayer, long startedAtMs, long durationMs,
                     long expiresAtMs, int lineCount, double seed, String prefix, String message, int wrapChars,
                     List<String> renderedLines) {
            this.hologram = hologram;
            this.captured = captured;
            this.offset = offset.clone();
            this.motion = motion;
            this.shimmer = shimmer;
            this.followPlayer = followPlayer;
            this.startedAtMs = startedAtMs;
            this.durationMs = durationMs;
            this.expiresAtMs = expiresAtMs;
            this.lineCount = Math.max(1, lineCount);
            this.seed = seed;
            this.prefix = prefix;
            this.message = message;
            this.wrapChars = wrapChars;
            this.dynamicPrefix = TextPipeline.viewerDependent(prefix);
            this.renderedLines = List.copyOf(renderedLines);
            this.shimmerBase = this.renderedLines;
            this.shimmerVisibleCount = shimmer.visibleCount(this.renderedLines);
        }

        boolean claimRetirement() {
            return retired.compareAndSet(false, true);
        }

        List<String> frameAt(long nowMs) {
            long ageMs = Math.max(0L, Math.min(durationMs, nowMs - startedAtMs));
            List<String> base = renderedLines;
            if (shimmerBase != base) {
                shimmerBase = base;
                shimmerVisibleCount = shimmer.visibleCount(base);
            }
            long bandIndex = shimmer.bandIndex(ageMs, durationMs, shimmerVisibleCount);
            ShimmerFrame cached = shimmerFrame;
            if (cached != null && cached.bandIndex() == bandIndex && cached.base() == base) {
                return cached.lines();
            }

            List<String> composed = shimmer.renderAt(base, bandIndex);
            shimmerFrame = new ShimmerFrame(bandIndex, base, composed);
            return composed;
        }
    }

    record ShimmerFrame(long bandIndex, List<String> base, List<String> lines) {
    }

    record SenderPublication(SenderState state, BubbleRecord retired) {
    }

    private record StyleSnapshot(Map<String, BubbleStyleDoc> documents, Map<String, ResolvedStyle> resolved) {
    }

    private record ResolvedStyle(BubbleStyleDoc document, BubbleMotionPlan motion, BubbleShimmerPlan shimmer) {
        private static final ResolvedStyle DEFAULT = new ResolvedStyle(
            BubbleStyleDoc.DEFAULTS, BubbleMotionPlan.compile(BubbleStyleDoc.DEFAULTS.motion(),
                "built-in bubble style revision " + BubbleStyleDoc.DEFAULTS.revision()),
            BubbleShimmerPlan.compile(BubbleStyleDoc.DEFAULTS.shimmer()));
    }

    record BubbleFrame(double stackY, BubbleMotionSample motion) {
    }
}
