package art.arcane.gloss.command;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

class TextArtRendererTest {
    private static final int DEFAULT_MAX_WIDTH = 48;

    @BeforeAll
    static void enableHeadlessMode() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void rendersTextIntoNonEmptyGrid() {
        List<String> rows = TextArtRenderer.render("HI", 1.0D, DEFAULT_MAX_WIDTH);

        Assertions.assertFalse(rows.isEmpty());
        Assertions.assertTrue(rows.stream().anyMatch(row -> row.indexOf('█') >= 0));
    }

    @Test
    void rowsNeverExceedWidthCap() {
        List<String> rows = TextArtRenderer.render("WWWWWWWWWWWWWWWWWWWW", 2.0D, DEFAULT_MAX_WIDTH);

        Assertions.assertFalse(rows.isEmpty());
        for (String row : rows) {
            Assertions.assertTrue(row.length() <= DEFAULT_MAX_WIDTH, "Row too wide: " + row.length());
        }
    }

    @Test
    void narrowWidthCapClampsRows() {
        List<String> rows = TextArtRenderer.render("WWWWWWWWWWWWWWWWWWWW", 2.0D, 8);

        Assertions.assertFalse(rows.isEmpty());
        for (String row : rows) {
            Assertions.assertTrue(row.length() <= 8, "Row too wide: " + row.length());
        }
    }

    @Test
    void widerCapAllowsWiderRowsThanNarrowCap() {
        List<String> narrow = TextArtRenderer.render("WWWWWWWWWWWWWWWWWWWW", 2.0D, 8);
        List<String> wide = TextArtRenderer.render("WWWWWWWWWWWWWWWWWWWW", 2.0D, 128);

        int narrowMax = narrow.stream().mapToInt(String::length).max().orElse(0);
        int wideMax = wide.stream().mapToInt(String::length).max().orElse(0);
        Assertions.assertTrue(wideMax > narrowMax, "Wider cap did not widen rows: " + wideMax + " vs " + narrowMax);
    }

    @Test
    void trimsSurroundingEmptyRowsAndColumns() {
        List<String> rows = TextArtRenderer.render("HI", 1.0D, DEFAULT_MAX_WIDTH);

        Assertions.assertTrue(rows.getFirst().indexOf('█') >= 0, "Top row was not trimmed to content");
        Assertions.assertTrue(rows.getLast().indexOf('█') >= 0, "Bottom row was not trimmed to content");
        Assertions.assertTrue(rows.stream().anyMatch(row -> !row.isEmpty() && row.charAt(0) == '█'),
                "Left column was not trimmed to content");
    }

    @Test
    void blankInputRendersNothing() {
        Assertions.assertTrue(TextArtRenderer.render("   ", 1.0D, DEFAULT_MAX_WIDTH).isEmpty());
        Assertions.assertTrue(TextArtRenderer.render(null, 1.0D, DEFAULT_MAX_WIDTH).isEmpty());
    }
}
