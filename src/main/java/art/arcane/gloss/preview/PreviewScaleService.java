package art.arcane.gloss.preview;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.menu.MenuSessionManager;
import art.arcane.volmlib.util.bukkit.Events;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PreviewScaleService {

  private static final double STEP = 1.10D;
  private static final double MAX_FACTOR = 2.50D;
  private static final double MIN_FACTOR = 0.25D;
  private static final double HIDE_BELOW = 0.30D;
  private static final long DOUBLE_TAP_MS = 400L;
  private static final long ADJUST_IDLE_TIMEOUT_MS = 20000L;
  private static final String PREVIEW_PURPOSE = "gloss:preview";
  private static final long PREVIEW_TTL_MILLIS = 1500L;

  private static final Map<UUID, Double> factors = new ConcurrentHashMap<>();
  private static final Map<UUID, Long> lastSneakPress = new ConcurrentHashMap<>();
  private static final Map<UUID, Long> adjusting = new ConcurrentHashMap<>();
  private static final List<Events> listeners = new CopyOnWriteArrayList<>();
  private static final AtomicBoolean writeQueued = new AtomicBoolean();
  private static final AtomicInteger revision = new AtomicInteger();
  private static volatile File storeFile;
  private static volatile Gloss owner;

  private PreviewScaleService() {
  }

  public static void init(Gloss plugin) {
    owner = plugin;
    storeFile = new File(plugin.getDataFolder(), "preview-scales.json");
    load();
    listeners.add(Events.listen(plugin, PlayerToggleSneakEvent.class, PreviewScaleService::onSneak));
    listeners.add(Events.listen(plugin, PlayerItemHeldEvent.class, PreviewScaleService::onScroll));
    listeners.add(Events.listen(plugin, PlayerQuitEvent.class, EventPriority.MONITOR, e -> {
      UUID id = e.getPlayer().getUniqueId();
      lastSneakPress.remove(id);
      if (adjusting.remove(id) != null) {
        persist();
      }
      plugin.getHudBar().clearAll(e.getPlayer());
    }));
  }

  public static void shutdown() {
    for (Events listener : listeners) {
      listener.unregister();
    }
    listeners.clear();
    writeQueued.set(false);
    write();
    lastSneakPress.clear();
    adjusting.clear();
    owner = null;
  }

  /**
   * Bumped whenever a stored factor changes. Open previews read a viewer's factor twice per tick;
   * holding the value against this counter turns that into one comparison until someone actually
   * scrolls.
   */
  public static int revision() {
    return revision.get();
  }

  public static float factor(Player player) {
    return factors.getOrDefault(player.getUniqueId(), 1.0D).floatValue();
  }

  public static boolean isHidden(Player player) {
    return factors.getOrDefault(player.getUniqueId(), 1.0D) < HIDE_BELOW;
  }

  private static void onSneak(PlayerToggleSneakEvent e) {
    if (!e.isSneaking()) {
      return;
    }
    Player player = e.getPlayer();
    UUID id = player.getUniqueId();
    long now = System.currentTimeMillis();
    Long previous = lastSneakPress.put(id, now);
    if (previous == null || now - previous > DOUBLE_TAP_MS) {
      return;
    }
    lastSneakPress.remove(id);
    if (!hasPreview(player)) {
      if (adjusting.remove(id) != null) {
        endAdjust(player);
      }
      return;
    }
    if (adjusting.remove(id) != null) {
      persist();
      actionBar(player, saveMessage(player));
    } else {
      adjusting.put(id, now);
      actionBar(player, Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEW_SCALE_ADJUSTING,
          MessageArgs.builder().untrusted("percent", percent(player)).build()
      ));
    }
  }

  private static void onScroll(PlayerItemHeldEvent e) {
    Player player = e.getPlayer();
    UUID id = player.getUniqueId();
    Long activity = adjusting.get(id);
    if (activity == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - activity > ADJUST_IDLE_TIMEOUT_MS || !hasPreview(player)) {
      adjusting.remove(id);
      persist();
      endAdjust(player);
      return;
    }
    if (!player.isSneaking()) {
      return;
    }
    int diff = e.getNewSlot() - e.getPreviousSlot();
    if (diff > 4) {
      diff -= 9;
    }
    if (diff < -4) {
      diff += 9;
    }
    if (diff == 0) {
      return;
    }
    e.setCancelled(true);
    adjusting.put(id, now);
    double current = factors.getOrDefault(id, 1.0D);
    double updated = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, current * Math.pow(STEP, -diff)));
    factors.put(id, updated);
    revision.incrementAndGet();
    if (updated < HIDE_BELOW) {
      actionBar(player, Gloss.instance.getLocalization().legacy(GlossMessages.PREVIEW_SCALE_HIDDEN));
    } else {
      actionBar(player, Gloss.instance.getLocalization().legacy(
          GlossMessages.PREVIEW_SCALE_SIZE,
          MessageArgs.builder().untrusted("percent", percent(player)).build()
      ));
    }
  }

  private static String saveMessage(Player player) {
    if (isHidden(player)) {
      return Gloss.instance.getLocalization().legacy(GlossMessages.PREVIEW_SCALE_SAVED_HIDDEN);
    }
    return Gloss.instance.getLocalization().legacy(
        GlossMessages.PREVIEW_SCALE_SAVED,
        MessageArgs.builder().untrusted("percent", percent(player)).build()
    );
  }

  private static int percent(Player player) {
    return (int) Math.round(factors.getOrDefault(player.getUniqueId(), 1.0D) * 100.0D);
  }

  private static boolean hasPreview(Player player) {
    MenuSessionManager manager = Gloss.instance == null ? null : Gloss.instance.getSessionManager();
    return manager != null && manager.hasPreviewSession(player);
  }

  private static void actionBar(Player player, String legacy) {
    Gloss.instance.getHudBar().publish(player, new HudSegment(PREVIEW_PURPOSE, HudPriority.INTERACTIVE, PREVIEW_TTL_MILLIS, List.of(HudSlot.CENTER, HudSlot.RIGHT), legacy));
  }

  private static void endAdjust(Player player) {
    Gloss plugin = Gloss.instance;
    if (plugin != null) {
      plugin.getHudBar().clear(player, PREVIEW_PURPOSE);
    }
  }

  private static void load() {
    File file = storeFile;
    if (file == null || !file.exists()) {
      return;
    }
    try {
      String json = Files.readString(file.toPath());
      Map<String, Double> raw = new Gson().fromJson(json, new TypeToken<Map<String, Double>>() {
      }.getType());
      if (raw == null) {
        return;
      }
      raw.forEach((key, value) -> {
        if (value == null || value.isNaN() || value.isInfinite()) {
          return;
        }
        try {
          factors.put(UUID.fromString(key), Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, value)));
        } catch (IllegalArgumentException ignored) {
        }
      });
      revision.incrementAndGet();
    } catch (Exception ex) {
      Gloss.logExceptionStack(false, ex, "Failed to load preview scales.");
    }
  }

  /**
   * Queues one write off the calling thread. Sneak and quit both request a persist from a gameplay
   * thread, and the store is one small JSON file rewritten whole, so requests arriving while a
   * write is already queued collapse into it — the queued write reads the live map, which by then
   * carries every change that collapsed. {@link #write} is the single writer and is synchronized,
   * so the last writer to acquire it is the one that leaves its content on disk.
   */
  private static void persist() {
    if (storeFile == null) {
      return;
    }
    if (!writeQueued.compareAndSet(false, true)) {
      return;
    }
    Gloss plugin = owner;
    if (plugin == null || !SchedulerUtils.runAsync(plugin, PreviewScaleService::flush)) {
      flush();
    }
  }

  private static void flush() {
    writeQueued.set(false);
    write();
  }

  private static synchronized void write() {
    File file = storeFile;
    if (file == null) {
      return;
    }
    try {
      Map<String, Double> raw = new TreeMap<>();
      factors.forEach((key, value) -> {
        if (value != 1.0D) {
          raw.put(key.toString(), Math.round(value * 100.0D) / 100.0D);
        }
      });
      File parent = file.getParentFile();
      if (parent != null) {
        parent.mkdirs();
      }
      Files.writeString(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(raw));
    } catch (Exception ex) {
      Gloss.logExceptionStack(false, ex, "Failed to save preview scales.");
    }
  }
}
