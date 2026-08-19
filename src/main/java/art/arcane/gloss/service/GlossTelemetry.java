package art.arcane.gloss.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class GlossTelemetry {
  private static final long RATE_WINDOW_MS = 1000L;
  private static final LongAdder PACKETS = new LongAdder();
  private static final LongAdder SPAWN_CHURN = new LongAdder();
  private static final LongAdder PREVIEW_REFRESHES = new LongAdder();
  private static final LongAdder TICK_NANOS = new LongAdder();
  private static final AtomicLong BUBBLES = new AtomicLong();
  private static final AtomicLong INDICATORS = new AtomicLong();
  private static final AtomicLong EMOJI_REPLACEMENTS = new AtomicLong();
  private static final AtomicInteger MENUS_OPEN = new AtomicInteger();
  private static final AtomicInteger PREVIEWS_OPEN = new AtomicInteger();
  private static final Object RATE_LOCK = new Object();
  private static volatile long windowDeadlineMs;
  private static long windowStartMs;
  private static long windowPackets;
  private static long windowSpawnChurn;
  private static long windowPreviewRefreshes;
  private static long windowTickNanos;
  private static long windowBubbles;
  private static long windowIndicators;
  private static long windowEmojiReplacements;
  private static volatile double packetsPerSecond;
  private static volatile double spawnsPerSecond;
  private static volatile double previewRefreshPerSecond;
  private static volatile double tickMsPerSecond;
  private static volatile double bubblesPerSecond;
  private static volatile double indicatorsPerSecond;
  private static volatile double emojiReplacementsPerSecond;

  private GlossTelemetry() {
  }

  public static void countPackets(long packets) {
    if (packets > 0L) {
      PACKETS.add(packets);
    }
  }

  public static void countSpawnChurn() {
    SPAWN_CHURN.increment();
  }

  public static void countPreviewRefresh() {
    PREVIEW_REFRESHES.increment();
  }

  public static void addTickNanos(long nanos) {
    if (nanos > 0L) {
      TICK_NANOS.add(nanos);
    }
  }

  public static void countBubbleSpawn() {
    BUBBLES.incrementAndGet();
  }

  public static void countIndicatorSpawn() {
    INDICATORS.incrementAndGet();
  }

  public static void countEmojiReplacement() {
    EMOJI_REPLACEMENTS.incrementAndGet();
  }

  public static void incrementMenusOpen() {
    MENUS_OPEN.incrementAndGet();
  }

  public static void decrementMenusOpen() {
    MENUS_OPEN.updateAndGet(current -> current > 0 ? current - 1 : 0);
  }

  public static void incrementPreviewsOpen() {
    PREVIEWS_OPEN.incrementAndGet();
  }

  public static void decrementPreviewsOpen() {
    PREVIEWS_OPEN.updateAndGet(current -> current > 0 ? current - 1 : 0);
  }

  public static int menusOpen() {
    return MENUS_OPEN.get();
  }

  public static int previewsOpen() {
    return PREVIEWS_OPEN.get();
  }

  public static double packetsPerSecond(long now) {
    refreshRates(now);
    return packetsPerSecond;
  }

  public static double spawnsPerSecond(long now) {
    refreshRates(now);
    return spawnsPerSecond;
  }

  public static double previewRefreshPerSecond(long now) {
    refreshRates(now);
    return previewRefreshPerSecond;
  }

  public static double tickMsPerSecond(long now) {
    refreshRates(now);
    return tickMsPerSecond;
  }

  public static double bubblesPerSecond(long now) {
    refreshRates(now);
    return bubblesPerSecond;
  }

  public static double indicatorsPerSecond(long now) {
    refreshRates(now);
    return indicatorsPerSecond;
  }

  public static double emojiReplacementsPerSecond(long now) {
    refreshRates(now);
    return emojiReplacementsPerSecond;
  }

  public static void clear() {
    synchronized (RATE_LOCK) {
      PACKETS.reset();
      SPAWN_CHURN.reset();
      PREVIEW_REFRESHES.reset();
      TICK_NANOS.reset();
      BUBBLES.set(0L);
      INDICATORS.set(0L);
      EMOJI_REPLACEMENTS.set(0L);
      windowDeadlineMs = 0L;
      windowStartMs = 0L;
      windowPackets = 0L;
      windowSpawnChurn = 0L;
      windowPreviewRefreshes = 0L;
      windowTickNanos = 0L;
      windowBubbles = 0L;
      windowIndicators = 0L;
      windowEmojiReplacements = 0L;
      packetsPerSecond = 0D;
      spawnsPerSecond = 0D;
      previewRefreshPerSecond = 0D;
      tickMsPerSecond = 0D;
      bubblesPerSecond = 0D;
      indicatorsPerSecond = 0D;
      emojiReplacementsPerSecond = 0D;
    }
    MENUS_OPEN.set(0);
    PREVIEWS_OPEN.set(0);
  }

  private static void refreshRates(long now) {
    long deadline = windowDeadlineMs;
    if (deadline != 0L && now < deadline) {
      return;
    }
    synchronized (RATE_LOCK) {
      if (windowStartMs == 0L) {
        windowStartMs = now;
        windowDeadlineMs = now + RATE_WINDOW_MS;
        windowPackets = PACKETS.sum();
        windowSpawnChurn = SPAWN_CHURN.sum();
        windowPreviewRefreshes = PREVIEW_REFRESHES.sum();
        windowTickNanos = TICK_NANOS.sum();
        windowBubbles = BUBBLES.get();
        windowIndicators = INDICATORS.get();
        windowEmojiReplacements = EMOJI_REPLACEMENTS.get();
        return;
      }

      long elapsed = now - windowStartMs;
      if (elapsed < RATE_WINDOW_MS) {
        return;
      }

      long packets = PACKETS.sum();
      long spawnChurn = SPAWN_CHURN.sum();
      long previewRefreshes = PREVIEW_REFRESHES.sum();
      long tickNanos = TICK_NANOS.sum();
      long bubbles = BUBBLES.get();
      long indicators = INDICATORS.get();
      long emojiReplacements = EMOJI_REPLACEMENTS.get();
      double seconds = elapsed / 1000D;

      packetsPerSecond = (packets - windowPackets) / seconds;
      spawnsPerSecond = (spawnChurn - windowSpawnChurn) / seconds;
      previewRefreshPerSecond = (previewRefreshes - windowPreviewRefreshes) / seconds;
      tickMsPerSecond = ((tickNanos - windowTickNanos) / 1.0E6D) / seconds;
      bubblesPerSecond = (bubbles - windowBubbles) / seconds;
      indicatorsPerSecond = (indicators - windowIndicators) / seconds;
      emojiReplacementsPerSecond = (emojiReplacements - windowEmojiReplacements) / seconds;

      windowStartMs = now;
      windowDeadlineMs = now + RATE_WINDOW_MS;
      windowPackets = packets;
      windowSpawnChurn = spawnChurn;
      windowPreviewRefreshes = previewRefreshes;
      windowTickNanos = tickNanos;
      windowBubbles = bubbles;
      windowIndicators = indicators;
      windowEmojiReplacements = emojiReplacements;
    }
  }
}
