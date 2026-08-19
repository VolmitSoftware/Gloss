package art.arcane.gloss.preview.doc;

/**
 * Detects a tick counter advancing faster than the game clock it is sampled against, the
 * signature of an external speed-up (hoppers feeding a boosted furnace, a plugin fast-forwarding
 * a brew). The thresholds, the 60-tick hold, and the seconds arithmetic are frozen: the
 * {@code furnace_surging} golden snapshot pins the suffix this produces.
 *
 * <p>Instances are single-threaded: one lives per preview context and is sampled from the region
 * thread that owns the previewed block.
 */
public final class TimeFlowTracker {

  private static final int SURGE_HOLD_TICKS = 60;

  private final boolean countsDown;
  private long lastGameTime = Long.MIN_VALUE;
  private int lastValue;
  private long surgeUntil = Long.MIN_VALUE;
  private double surgeSeconds;

  /**
   * @param countsDown true for counters that tick towards zero (brewing time), false for counters
   *     that tick upwards (furnace cook time)
   */
  public TimeFlowTracker(boolean countsDown) {
    this.countsDown = countsDown;
  }

  /**
   * Records one observation. Repeated samples at the same {@code gameTime} are ignored, so the
   * tracker measures counter progress per game tick rather than per read.
   */
  public void sample(long gameTime, int value) {
    if (gameTime == lastGameTime) {
      return;
    }
    if (lastGameTime != Long.MIN_VALUE && value > 0) {
      long elapsed = gameTime - lastGameTime;
      long gained = countsDown ? (long) lastValue - value : (long) value - lastValue;
      if (elapsed > 0 && elapsed <= 100 && gained > elapsed + 1) {
        surgeSeconds = (gained - elapsed) / 20.0;
        surgeUntil = gameTime + SURGE_HOLD_TICKS;
      }
    }
    lastGameTime = gameTime;
    lastValue = value;
  }

  /** True while the last detected surge is still within its {@value #SURGE_HOLD_TICKS}-tick hold. */
  public boolean surging() {
    return lastGameTime <= surgeUntil;
  }

  /**
   * Seconds of counter progress gained beyond the real elapsed ticks in the sample window that
   * triggered the surge: {@code (gained - elapsed) / 20}. This is the exact value the retired
   * {@code withSurgeSuffix} rendering formatted into its {@code "+N.Ns"} suffix, and it is what
   * the {@code surge.gain} preview variable exposes.
   */
  public double surgeSeconds() {
    return surgeSeconds;
  }
}
