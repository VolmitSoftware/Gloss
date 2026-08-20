package art.arcane.gloss.bubble;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BubbleShimmerPlanTest {
    private static BubbleShimmerPlan plan(int width) {
        return BubbleShimmerPlan.compile(
            new BubbleStyleDoc.Shimmer(true, true, "#ffffff", width, 1000L, 100L, 1000L));
    }

    @Test
    void spawnAndFlyAwayWindowsEachSweepFromLeftToRight() {
        BubbleShimmerPlan plan = plan(1);

        assertEquals(-1.0D, plan.progress(99L, 5000L));
        assertEquals(0.0D, plan.progress(100L, 5000L));
        assertEquals(0.5D, plan.progress(600L, 5000L));
        assertEquals(1.0D, plan.progress(1100L, 5000L));
        assertEquals(-1.0D, plan.progress(1101L, 5000L));
        assertEquals(0.0D, plan.progress(4000L, 5000L));
        assertEquals(0.5D, plan.progress(4500L, 5000L));
        assertEquals(1.0D, plan.progress(5000L, 5000L));
    }

    @Test
    void whiteBandRestoresTheActiveColorAndDecorationsAfterEachGlyph() {
        BubbleShimmerPlan plan = plan(1);
        String input = "§aHi §lBob";

        String spawn = plan.render(List.of(input), 100L, 5000L).getFirst();
        String departure = plan.render(List.of(input), 5000L, 5000L).getFirst();

        assertEquals("§a§x§f§f§f§f§f§fH§ai §lBob", spawn);
        assertEquals("§aHi §lBo§x§f§f§f§f§f§fb§a§l", departure);
    }

    @Test
    void eachLineInTheSingleTextBlockUsesTheSameHorizontalSweepProgress() {
        BubbleShimmerPlan plan = plan(1);
        List<String> input = List.of("§7Alpha", "§b🚀 Beta");

        List<String> rendered = plan.render(input, 600L, 5000L);

        assertEquals(2, rendered.size());
        assertNotEquals(input.get(0), rendered.get(0));
        assertNotEquals(input.get(1), rendered.get(1));
    }

    @Test
    void outsideBothBoundedWindowsReturnsTheUnchangedTextBlock() {
        BubbleShimmerPlan plan = plan(3);
        List<String> input = List.of("§7No active sweep");

        assertSame(input, plan.render(input, 2500L, 5000L));
    }
}
