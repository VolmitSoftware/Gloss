package art.arcane.gloss.bubble;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.util.Ticks;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ChatBubblesService implements Listener {
    private static final String SEND_PERMISSION = "gloss.bubbles.send";
    private static final int EYE_REFRESH_INTERVAL_TICKS = 1;

    private final Gloss plugin;
    private final Map<UUID, SenderState> bubbles = new ConcurrentHashMap<>();

    private boolean hookRegistered;
    private int eyeTaskId = -1;

    public ChatBubblesService(Gloss plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!plugin.cfg().bubbles().enabled()) {
            return;
        }
        registerHook();
        startEyeTask();
    }

    public void disable() {
        stopEyeTask();
        HandlerList.unregisterAll(this);
        destroyAll();
        hookRegistered = false;
    }

    public void reload() {
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
        GlossConfig.Bubbles cfg = plugin.cfg().bubbles();
        if (!cfg.enabled()) {
            return;
        }

        List<String> lines = BubbleLines.split(message, cfg.wordWrapChars());
        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            int index = lineNumber;
            FoliaScheduler.runEntity(plugin, sender, () -> spawn(sender, line, index), (long) cfg.lineStaggerTicks() * lineNumber);
        }
    }

    private void spawn(Player sender, String line, int lineNumber) {
        GlossConfig.Bubbles cfg = plugin.cfg().bubbles();
        if (!cfg.enabled() || !sender.isOnline()) {
            return;
        }
        if (cfg.blacklistWorlds().contains(sender.getWorld().getName())) {
            return;
        }
        if (!sender.hasPermission(SEND_PERMISSION)) {
            return;
        }

        Location captured = sender.getEyeLocation().add(cfg.offsetX(), cfg.offsetY(), cfg.offsetZ());
        String id = "chat-" + sender.getUniqueId() + "-" + M.ms() + "-" + lineNumber;
        TemporaryHologram hologram = plugin.holograms().createTemporary(id, captured.clone(), cfg.maxTimeAliveMs());
        hologram.addLine(cfg.prefix() + line);
        if (cfg.hideOwn()) {
            hologram.viewers().add(sender.getUniqueId());
        }

        UUID senderId = sender.getUniqueId();
        SenderState state = bubbles.computeIfAbsent(senderId, ignored -> new SenderState());
        state.lastEye = sender.getEyeLocation();
        BubbleRecord record = new BubbleRecord(hologram, captured);
        state.live.add(record);
        hologram.bindPosition(() -> bubblePosition(state, record));
        plugin.scheduler().a(() -> untrack(senderId, record), Ticks.fromMs(cfg.maxTimeAliveMs()));
    }

    private Location bubblePosition(SenderState state, BubbleRecord record) {
        double spread = plugin.holograms().stackSpread();
        int lineIndex = Math.max(state.live.indexOf(record), 0);
        int liveCount = state.live.size();
        GlossConfig.Bubbles cfg = plugin.cfg().bubbles();
        double lift = BubbleStackMath.offsetY(spread, lineIndex, liveCount, record.hologram.remainingMs(), cfg.flyAway());
        Location base = cfg.followPlayers() ? state.lastEye : record.captured;
        if (base == null) {
            base = record.captured;
        }
        return base.clone().add(0.0D, lift, 0.0D);
    }

    private void refreshEyes() {
        if (!plugin.cfg().bubbles().followPlayers()) {
            return;
        }
        for (Map.Entry<UUID, SenderState> entry : bubbles.entrySet()) {
            SenderState state = entry.getValue();
            if (state.live.isEmpty()) {
                continue;
            }
            Player sender = plugin.getServer().getPlayer(entry.getKey());
            if (sender == null) {
                continue;
            }
            FoliaScheduler.runEntity(plugin, sender, () -> state.lastEye = sender.getEyeLocation());
        }
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

    private static final class SenderState {
        private final List<BubbleRecord> live = new CopyOnWriteArrayList<>();
        private volatile Location lastEye;
    }

    private static final class BubbleRecord {
        private final TemporaryHologram hologram;
        private final Location captured;

        private BubbleRecord(TemporaryHologram hologram, Location captured) {
            this.hologram = hologram;
            this.captured = captured;
        }
    }
}
