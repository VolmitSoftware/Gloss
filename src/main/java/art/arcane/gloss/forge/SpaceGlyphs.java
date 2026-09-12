package art.arcane.gloss.forge;

/**
 * Emits the negative-space glyph string that moves the cursor by {@code px} pixels, or an empty
 * string when no space provider is available.
 */
@FunctionalInterface
public interface SpaceGlyphs {
    SpaceGlyphs NONE = px -> "";

    String shift(int px);
}
