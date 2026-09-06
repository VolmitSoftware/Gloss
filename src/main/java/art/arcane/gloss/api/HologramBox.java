package art.arcane.gloss.api;


public record HologramBox(Boolean enabled, Integer padding, Integer borderWidth,
                          IconArgbColor backgroundArgb, IconArgbColor borderArgb) {
    private static final HologramBox DEFAULTS = new HologramBox(false, 4, 1,
        new IconArgbColor(0xB31B1B22), new IconArgbColor(0xFFAAAAAA));

    public HologramBox {
        enabled = enabled == null ? false : enabled;
        padding = range(padding == null ? 4 : padding, 0, 64, "padding");
        borderWidth = range(borderWidth == null ? 1 : borderWidth, 0, 16, "borderWidth");
        backgroundArgb = backgroundArgb == null ? new IconArgbColor(0xB31B1B22) : backgroundArgb;
        borderArgb = borderArgb == null ? new IconArgbColor(0xFFAAAAAA) : borderArgb;
    }

    public static HologramBox defaults() {
        return DEFAULTS;
    }

    private static int range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Box " + name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
