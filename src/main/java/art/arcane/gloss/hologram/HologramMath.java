package art.arcane.gloss.hologram;

import java.util.List;

public final class HologramMath {
    private static final double PAPER_VIEW_RANGE_BASE_BLOCKS = 64.0D;

    private HologramMath() {
    }

    public static float viewRangeMultiplier(double viewRangeBlocks) {
        return (float) (viewRangeBlocks / PAPER_VIEW_RANGE_BASE_BLOCKS);
    }

    public static boolean requiresViewerRendering(List<String> lines) {
        for (String line : lines) {
            if (line.indexOf('%') >= 0) {
                return true;
            }
        }

        return false;
    }
}
