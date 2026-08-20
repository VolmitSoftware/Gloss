package art.arcane.gloss.bubble;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.hologram.AnimatorLoopPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleShimmerPlanTest {
    private static final String WHITE = "§x§f§f§f§f§f§f";
    private static final String TINT = "§x§f§f§8§8§f§f";
    private static final String GLYPHS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static BubbleShimmerPlan shipped() {
        return BubbleShimmerPlan.compile(BubbleStyleDoc.DEFAULTS.shimmer());
    }

    private static String line(int from, int glyphCount) {
        StringBuilder text = new StringBuilder("§7");
        for (int index = 0; index < glyphCount; index++) {
            text.append(GLYPHS.charAt(from + index));
        }
        return text.toString();
    }

    private static List<String> block(int lines, int glyphsPerLine) {
        List<String> rows = new ArrayList<>(lines);
        for (int row = 0; row < lines; row++) {
            rows.add(line(row * glyphsPerLine, glyphsPerLine));
        }
        return List.copyOf(rows);
    }

    private static List<Integer> glyphsWearing(List<String> rendered, String code, int totalGlyphs) {
        List<Integer> worn = new ArrayList<>();
        for (int index = 0; index < totalGlyphs; index++) {
            String needle = code + GLYPHS.charAt(index);
            for (String row : rendered) {
                if (row.contains(needle)) {
                    worn.add(index);
                    break;
                }
            }
        }
        return worn;
    }

    private static int occurrences(List<String> lines, String needle) {
        int count = 0;
        for (String line : lines) {
            int cursor = 0;
            while ((cursor = line.indexOf(needle, cursor)) >= 0) {
                count++;
                cursor += needle.length();
            }
        }
        return count;
    }

    @Test
    void defaultWaitsThenRunsOneSpawnAndOneDepartureSweep() {
        BubbleShimmerPlan plan = shipped();
        int visibleGlyphs = 26;

        assertEquals(BubbleShimmerPlan.NO_BAND, plan.bandIndex(399L, 5000L, visibleGlyphs));
        assertEquals(0L, plan.bandIndex(400L, 5000L, visibleGlyphs));
        assertEquals(13L, plan.bandIndex(750L, 5000L, visibleGlyphs));
        assertEquals(25L, plan.bandIndex(1100L, 5000L, visibleGlyphs));
        assertEquals(BubbleShimmerPlan.NO_BAND, plan.bandIndex(1101L, 5000L, visibleGlyphs));
        assertEquals(BubbleShimmerPlan.NO_BAND, plan.bandIndex(4299L, 5000L, visibleGlyphs));
        assertEquals(0L, plan.bandIndex(4300L, 5000L, visibleGlyphs));
        assertEquals(13L, plan.bandIndex(4650L, 5000L, visibleGlyphs));
        assertEquals(25L, plan.bandIndex(5000L, 5000L, visibleGlyphs));
    }

    @Test
    void defaultBandIsSolidWhiteAndThreeGlyphsWide() {
        BubbleShimmerPlan plan = shipped();
        List<String> rendered = plan.renderAt(List.of(line(0, 26)), 3L);

        assertEquals(List.of(2, 3, 4), glyphsWearing(rendered, WHITE, 26));
        assertTrue(rendered.getFirst().contains(WHITE + "c§7" + WHITE + "d§7" + WHITE + "e§7"));
    }

    @Test
    void oneBandCrossesWrappedRowsWithoutRestarting() {
        BubbleShimmerPlan plan = shipped();
        List<String> rendered = plan.renderAt(block(2, 8), 8L);

        assertEquals(List.of(7, 8, 9), glyphsWearing(rendered, WHITE, 16));
    }

    @Test
    void longMultilineBlocksNeverCreateModuloCopies() {
        BubbleShimmerPlan plan = shipped();
        String longLine = "a".repeat(40);
        List<String> rendered = plan.renderAt(List.of(longLine, longLine, longLine, longLine), 130L);

        assertEquals(3, occurrences(rendered, WHITE));
    }

    @Test
    void customBandColorAndWidthApplyToEveryLitGlyph() {
        BubbleShimmerPlan plan = BubbleShimmerPlan.compile(new BubbleStyleDoc.Shimmer(
            true, false, "#FF88FF", 4, 900L, 0L, 700L));

        assertEquals(List.of(9, 10, 11, 12),
            glyphsWearing(plan.renderAt(List.of(line(0, 26)), 10L), TINT, 26));
    }

    @Test
    void bandRestoresTheActiveColorAndDecorationsAfterEveryGlyph() {
        BubbleShimmerPlan plan = shipped();

        assertEquals("§7" + WHITE + "H§7" + WHITE + "e§7llo",
            plan.renderAt(List.of("§7Hello"), 0L).getFirst());
        assertEquals("§aHi" + WHITE + " §a§l" + WHITE + "B§a§l" + WHITE + "o§a§lb",
            plan.renderAt(List.of("§aHi §lBob"), 3L).getFirst());
    }

    @Test
    void disabledWindowsReturnTheOriginalBlock() {
        BubbleShimmerPlan plan = BubbleShimmerPlan.compile(new BubbleStyleDoc.Shimmer(
            false, false, "#ffffff", 3, 700L, 400L, 700L));
        List<String> lines = List.of("§7message");

        assertEquals(BubbleShimmerPlan.NO_BAND, plan.bandIndex(2500L, 5000L, 7));
        assertSame(lines, plan.render(lines, 2500L, 5000L));
    }

    @Test
    void animatorSamplesAHighFrequencyMultilineSweep() {
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        GlossConfig config = GlossConfig.from(file);
        long floorMs = AnimatorLoopPolicy.floorMillis(config.holograms().maxAnimationFps());
        BubbleShimmerPlan plan = shipped();
        Set<Long> sampledBands = new HashSet<>();

        for (long ageMs = 400L; ageMs <= 1100L; ageMs += floorMs) {
            sampledBands.add(plan.bandIndex(ageMs, 5000L, 160));
        }

        assertEquals(8L, floorMs);
        assertTrue(sampledBands.size() >= 80);
    }
}
