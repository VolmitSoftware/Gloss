package art.arcane.gloss.hologram;

public final class AnimatorLoopPolicy {
    public static final long CEILING_MILLIS = 250L;
    public static final long MIN_FLOOR_MILLIS = 4L;
    public static final double BUDGET_FACTOR = 1.25D;
    public static final double SHRINK_THRESHOLD = 0.5D;
    public static final double RECOVERY_FACTOR = 0.75D;

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
        if (passMillis > current * BUDGET_FACTOR) {
            return Math.min(CEILING_MILLIS, current + floor);
        }
        if (current <= floor || passMillis >= current * SHRINK_THRESHOLD) {
            return current;
        }

        long recovered = (long) Math.floor(current * RECOVERY_FACTOR);
        return Math.max(floor, Math.min(current - 1L, recovered));
    }

    public static long minSendIntervalMillis(long packetRecipients, int packetBudget) {
        if (packetRecipients <= 0L) {
            return 0L;
        }

        long budget = Math.max(1L, packetBudget);
        return (packetRecipients * 1000L) / budget;
    }
}
