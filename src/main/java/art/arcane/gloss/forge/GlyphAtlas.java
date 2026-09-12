package art.arcane.gloss.forge;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Looks an {@code images/} path up as a glyph. A menu icon reaches this from deep inside a render
 * with no service handle, so the lookup is static and answers empty until the forge lane installs
 * one. Only images a {@code glyphs/} document declares resolve: auto-declaring would allocate
 * codepoints an operator never asked for, and the ledger never gives them back.
 */
public final class GlyphAtlas {
    private static volatile GlyphAtlas active;

    private final Supplier<GlyphRegistry> registry;
    private final LayoutFunctions layout;

    public GlyphAtlas(Supplier<GlyphRegistry> registry, LayoutFunctions layout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    /** The font-tagged glyph string for an image a glyph document declares. */
    public static Optional<String> glyphFor(String imagePath) {
        GlyphAtlas atlas = active;
        return atlas == null ? Optional.empty() : atlas.resolve(imagePath);
    }

    /** The resolved glyph behind an image, for callers that need its height or fallback. */
    public static Optional<GlyphRegistry.ResolvedGlyph> lookup(String imagePath) {
        GlyphAtlas atlas = active;
        return atlas == null ? Optional.empty() : atlas.registry.get().byImage(imagePath);
    }

    public void install() {
        active = this;
    }

    public void uninstall() {
        if (active == this) {
            active = null;
        }
    }

    private Optional<String> resolve(String imagePath) {
        return registry.get().byImage(imagePath).flatMap(glyph -> layout.fontTag(glyph.id()));
    }
}
