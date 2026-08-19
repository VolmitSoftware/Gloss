package art.arcane.gloss.hologram;

public final class AnimatorLoopPolicy {
    public static final long CEILING_MILLIS = 250L;
    public static final long MIN_FLOOR_MILLIS = 4L;
    public static final double BUDGET_FACTOR = 1.25D;

    private AnimatorLoopPolicy() {
    }

    public static long floorMillis(int maxAnimationFps) {
        int fps = Math.max(1, Math.min(240, maxAnimationFps));
        long floor = Math.round(1000.0D / fps);
        return Math.max(MIN_FLOOR_MILLIS, Math.min(CEILING_MILLIS, floor));
    }

    public static long nextIntervalMillis(long currentMillis, double passMillis, long floorMillis) {
        long floor = Math.max(MIN_FLOOR_MILLIS, Math.min(CEILING_MILLIS, floorMillis));
        long current = Math.max(floor, Math.min(CEILING_MILLIS, currentMillis));
        long adjusted = passMillis > current * BUDGET_FACTOR ? current + floor : current - floor;
        return Math.max(floor, Math.min(CEILING_MILLIS, adjusted));
    }

    public static long minSendIntervalMillis(int viewers, int packetBudget) {
        if (viewers <= 0) {
            return 0L;
        }

        return (viewers * 1000L) / Math.max(1L, packetBudget);
    }
}
