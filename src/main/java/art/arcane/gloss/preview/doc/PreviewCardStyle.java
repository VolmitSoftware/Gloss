package art.arcane.gloss.preview.doc;

import art.arcane.gloss.api.IconArgbColor;

public record PreviewCardStyle(Integer padding, Integer borderWidth, Integer trayPadding,
                               Integer titleHeight, Integer titleGap, IconArgbColor backgroundArgb,
                               IconArgbColor trayArgb, IconArgbColor borderArgb, IconArgbColor titleArgb) {
    public PreviewCardStyle {
        padding = dimension(padding, 7, "padding");
        borderWidth = dimension(borderWidth, 3, "borderWidth");
        trayPadding = dimension(trayPadding, 4, "trayPadding");
        titleHeight = dimension(titleHeight, 17, "titleHeight");
        titleGap = dimension(titleGap, 6, "titleGap");
        backgroundArgb = backgroundArgb == null ? new IconArgbColor(0xF21B1B22) : backgroundArgb;
        trayArgb = trayArgb == null ? new IconArgbColor(0xFF33333E) : trayArgb;
    }

    private static int dimension(Integer value, int fallback, String name) {
        int resolved = value == null ? fallback : value;
        if (resolved < 0 || resolved > 256) {
            throw new IllegalArgumentException("card." + name + " must be between 0 and 256 pixels");
        }
        return resolved;
    }
}
