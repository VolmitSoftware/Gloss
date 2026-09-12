package art.arcane.gloss.menu.icon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An oversize textImage turns into the missing-icon checkerboard, which tells the operator nothing.
 * Each offending file is named once and counted, so /gloss status shows the pile without spamming
 * the log on every menu render.
 */
class TextImageOversizeReportTest {
    @Test
    void oversizeImagesAreCountedOncePerPath() {
        int before = TextImageRasterCache.oversizeCount();

        TextImageRasterCache.reportOversize("big/logo.png", 32, 32);
        TextImageRasterCache.reportOversize("big/logo.png", 32, 32);
        TextImageRasterCache.reportOversize("big/other.png", 64, 16);

        assertEquals(before + 2, TextImageRasterCache.oversizeCount());
    }
}
