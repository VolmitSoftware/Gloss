package art.arcane.gloss.bubble;

public final class BubbleStackMath {
    private static final double BASE_LIFT = 0.86D;
    private static final long FLY_AWAY_WINDOW_MS = 2000L;
    private static final double FLY_AWAY_EXPONENT = 16.0D;
    private static final double FLY_AWAY_HEIGHT = 10.0D;

    private BubbleStackMath() {
    }

    public static double stackOffset(double spread, int lineIndex, int liveCount) {
        return Math.max(spread * (liveCount - lineIndex), 0.0D);
    }

    public static double flyAway(long remainingMs) {
        if (remainingMs >= FLY_AWAY_WINDOW_MS) {
            return 0.0D;
        }
        long remaining = Math.max(remainingMs, 0L);
        return Math.pow(1.0D - (remaining / (double) FLY_AWAY_WINDOW_MS), FLY_AWAY_EXPONENT) * FLY_AWAY_HEIGHT;
    }

    public static double offsetY(double spread, int lineIndex, int liveCount, long remainingMs, boolean flyAwayEnabled) {
        double flyLift = flyAwayEnabled ? flyAway(remainingMs) : 0.0D;
        return BASE_LIFT + stackOffset(spread, lineIndex, liveCount) + flyLift;
    }
}
