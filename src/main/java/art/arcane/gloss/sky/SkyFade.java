package art.arcane.gloss.sky;

/**
 * Linear interpolation on the day clock. A fade from dusk to dawn takes the short way round
 * midnight, which is what an author means by "fade to morning" and what a naive lerp gets wrong.
 */
public final class SkyFade {
    public static final long DAY_TICKS = 24000L;

    private SkyFade() {
    }

    public static long timeAt(long from, long to, int fadeTicks, int elapsedTicks) {
        if (fadeTicks <= 0 || elapsedTicks >= fadeTicks) {
            return Math.floorMod(to, DAY_TICKS);
        }
        long start = Math.floorMod(from, DAY_TICKS);
        long delta = Math.floorMod(to - start, DAY_TICKS);
        if (delta > DAY_TICKS / 2L) {
            delta -= DAY_TICKS;
        }
        double progress = (double) Math.max(0, elapsedTicks) / fadeTicks;
        return Math.floorMod(start + Math.round(delta * progress), DAY_TICKS);
    }
}
