package art.arcane.gloss.bubble;

public final class BubbleStackMath {
    private static final double BASE_LIFT = 0.86D;

    private BubbleStackMath() {
    }

    public static double stackOffset(double spread, int stackedLineCount) {
        return Math.max(spread * stackedLineCount, 0.0D);
    }

    public static double offsetY(double spread, int stackedLineCount) {
        return BASE_LIFT + stackOffset(spread, stackedLineCount);
    }
}
