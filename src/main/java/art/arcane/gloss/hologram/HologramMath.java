package art.arcane.gloss.hologram;

import art.arcane.gloss.text.TextPipeline;

import java.util.List;
import java.util.function.Predicate;

public final class HologramMath {
    private static final double PAPER_VIEW_RANGE_BASE_BLOCKS = 64.0D;

    private HologramMath() {
    }

    public static float viewRangeMultiplier(double viewRangeBlocks) {
        return (float) (viewRangeBlocks / PAPER_VIEW_RANGE_BASE_BLOCKS);
    }

    public static int classify(List<String> lines) {
        int flags = 0;
        for (String line : lines) {
            flags |= TextPipeline.classify(line);
        }

        return flags;
    }

    public static boolean containsRegisteredFunction(String line, Predicate<String> registered) {
        int open = line.indexOf('|');
        while (open >= 0) {
            int close = line.indexOf('|', open + 1);
            if (close < 0) {
                return false;
            }
            if (registered.test(line.substring(open + 1, close))) {
                return true;
            }

            open = close;
        }

        return false;
    }
}
