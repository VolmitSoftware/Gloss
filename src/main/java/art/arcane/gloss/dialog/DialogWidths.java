package art.arcane.gloss.dialog;

/** The width range the dialog protocol accepts, applied to every authored width. */
final class DialogWidths {
    static final int MINIMUM = 1;
    static final int MAXIMUM = 1024;

    private DialogWidths() {
    }

    static int clamp(Integer width, int fallback) {
        return width == null ? fallback : Math.clamp(width.intValue(), MINIMUM, MAXIMUM);
    }
}
