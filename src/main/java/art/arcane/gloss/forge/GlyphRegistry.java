package art.arcane.gloss.forge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every glyph the loaded {@code glyphs/} documents declare, resolved against the ledger: a stable
 * codepoint per sheet cell, the advance width the font metrics report for it, and the lookups the
 * layout functions and the emoji substitution need. Building one is pure apart from the ledger
 * allocations it makes, so the pack build and the runtime always agree on what a glyph id means.
 */
public final class GlyphRegistry {
    public static final GlyphRegistry EMPTY = new GlyphRegistry(GlyphDoc.DEFAULT_NAMESPACE, GlyphDoc.DEFAULT_FONT,
        Map.of(), Map.of(), Map.of(), new Spaces(false, 0, 0, Map.of()));

    private final String namespace;
    private final String font;
    private final Map<String, ResolvedGlyph> glyphs;
    private final Map<String, ResolvedGlyph> overlays;
    private final Map<String, String> emoji;
    private final Spaces spaces;
    private final Map<String, ResolvedGlyph> byImage;

    private GlyphRegistry(String namespace, String font, Map<String, ResolvedGlyph> glyphs,
                          Map<String, ResolvedGlyph> overlays, Map<String, String> emoji, Spaces spaces) {
        this.namespace = namespace;
        this.font = font;
        this.glyphs = Map.copyOf(glyphs);
        this.overlays = Map.copyOf(overlays);
        this.emoji = Map.copyOf(emoji);
        this.spaces = spaces;
        Map<String, ResolvedGlyph> images = new LinkedHashMap<>();
        for (ResolvedGlyph glyph : glyphs.values()) {
            images.putIfAbsent(glyph.image(), glyph);
        }
        this.byImage = Map.copyOf(images);
    }

    /** Reads the pixel size of an image under {@code images/}; null when it cannot be read. */
    @FunctionalInterface
    public interface ImageProbe {
        int[] size(String imagePath);

        ImageProbe NONE = imagePath -> null;
    }

    /** The space provider's range and the codepoint that carries each advance. */
    public record Spaces(boolean enabled, int minimum, int maximum, Map<Integer, Integer> codepoints) {
        public Spaces {
            codepoints = Map.copyOf(codepoints);
        }

        public boolean covers(int px) {
            return enabled && px >= minimum && px <= maximum;
        }

        public Integer codepoint(int px) {
            return codepoints.get(px);
        }
    }

    /**
     * A glyph ready to render. {@code codepoints} carries one entry per sheet cell, left to right;
     * a single-cell glyph has exactly one.
     */
    public record ResolvedGlyph(String id, String image, int height, int ascent, String fallback,
                                int widthPx, int frames, List<Integer> codepoints, boolean overlay,
                                String anchor) {
        public ResolvedGlyph {
            codepoints = List.copyOf(codepoints);
        }

        public String character() {
            return character(0);
        }

        public String character(int frame) {
            return new String(Character.toChars(codepoints.get(Math.clamp(frame, 0, codepoints.size() - 1))));
        }
    }

    public static GlyphRegistry build(Map<String, GlyphDoc> documents, GlyphLedger ledger, ImageProbe probe) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(ledger, "ledger");
        ImageProbe sizes = probe == null ? ImageProbe.NONE : probe;
        if (documents.isEmpty()) {
            return EMPTY;
        }

        Map<String, ResolvedGlyph> glyphs = new LinkedHashMap<>();
        Map<String, ResolvedGlyph> overlays = new LinkedHashMap<>();
        Map<String, String> emoji = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, GlyphDoc> ordered = new TreeMap<>(documents);
        String namespace = null;
        String font = null;
        String source = null;
        boolean spacesEnabled = false;
        int minimum = 0;
        int maximum = 0;

        for (Map.Entry<String, GlyphDoc> entry : ordered.entrySet()) {
            String documentId = entry.getKey();
            GlyphDoc doc = entry.getValue();
            if (namespace == null) {
                namespace = doc.namespace();
                font = doc.font();
                source = documentId;
            } else if (!namespace.equals(doc.namespace()) || !font.equals(doc.font())) {
                throw new IllegalArgumentException("glyphs/" + documentId + " targets " + doc.namespace() + ":"
                    + doc.font() + " but glyphs/" + source + " targets " + namespace + ":" + font
                    + "; one pack carries one font");
            }
            for (String id : doc.declaredIds()) {
                String previous = owners.putIfAbsent(id, documentId);
                if (previous != null) {
                    throw new IllegalArgumentException("glyph id " + id + " is declared by glyphs/" + previous
                        + " and glyphs/" + documentId);
                }
            }
            if (doc.space().enabled()) {
                minimum = spacesEnabled ? Math.min(minimum, doc.space().minimum()) : doc.space().minimum();
                maximum = spacesEnabled ? Math.max(maximum, doc.space().maximum()) : doc.space().maximum();
                spacesEnabled = true;
            }
        }

        boolean allocateSpaces = spacesEnabled;
        int spaceMinimum = minimum;
        int spaceMaximum = maximum;
        Map<Integer, Integer> spaceCodepoints = new TreeMap<>();
        ledger.batch(() -> {
            for (Map.Entry<String, GlyphDoc> entry : ordered.entrySet()) {
                GlyphDoc doc = entry.getValue();
                for (GlyphDoc.Glyph glyph : doc.glyphs()) {
                    ResolvedGlyph resolved = resolve(glyph.id(), glyph.image(), glyph.height(), glyph.ascent(),
                        glyph.fallback(), glyph.width(), glyph.frames(), false, null, ledger, sizes);
                    glyphs.put(resolved.id(), resolved);
                    if (glyph.emoji() != null) {
                        emoji.put(glyph.emoji(), resolved.id());
                    }
                }
                for (GlyphDoc.Overlay overlay : doc.overlays()) {
                    ResolvedGlyph resolved = resolve(overlay.id(), overlay.image(), overlay.height(),
                        overlay.ascent(), "", null, 1, true, overlay.anchor(), ledger, sizes);
                    overlays.put(resolved.id(), resolved);
                }
            }
            if (allocateSpaces) {
                for (int px = spaceMinimum; px <= spaceMaximum; px++) {
                    spaceCodepoints.put(px, ledger.spaceCodepoint(px));
                }
            }
        });

        return new GlyphRegistry(namespace, font, glyphs, overlays, emoji,
            new Spaces(allocateSpaces, spaceMinimum, spaceMaximum, spaceCodepoints));
    }

    public String namespace() {
        return namespace;
    }

    public String font() {
        return font;
    }

    public Spaces spaces() {
        return spaces;
    }

    public boolean isEmpty() {
        return glyphs.isEmpty() && overlays.isEmpty() && !spaces.enabled();
    }

    public Optional<ResolvedGlyph> glyph(String id) {
        return Optional.ofNullable(id == null ? null : glyphs.get(id));
    }

    public Optional<ResolvedGlyph> overlay(String id) {
        return Optional.ofNullable(id == null ? null : overlays.get(id));
    }

    /** The glyph a declared {@code images/} path renders as, for the textImage lift. */
    public Optional<ResolvedGlyph> byImage(String imagePath) {
        return Optional.ofNullable(imagePath == null ? null : byImage.get(imagePath));
    }

    /** The glyph id an emoji trigger substitutes to for pack viewers. */
    public Optional<String> emojiGlyph(String trigger) {
        return Optional.ofNullable(trigger == null ? null : emoji.get(trigger));
    }

    /** Every emoji id a glyph stands in for, mapped to that glyph's id. */
    public Map<String, String> emojiMappings() {
        return emoji;
    }

    /** Glyphs then overlays, both in id order, which is the order the pack build writes them in. */
    public List<ResolvedGlyph> all() {
        List<ResolvedGlyph> all = new ArrayList<>(glyphs.size() + overlays.size());
        all.addAll(new TreeMap<>(glyphs).values());
        all.addAll(new TreeMap<>(overlays).values());
        return List.copyOf(all);
    }

    /** The advance widths the font metrics table needs, keyed by codepoint. */
    public Map<Integer, Integer> declaredWidths() {
        Map<Integer, Integer> widths = new LinkedHashMap<>();
        for (ResolvedGlyph glyph : all()) {
            for (int codepoint : glyph.codepoints()) {
                widths.put(codepoint, glyph.widthPx());
            }
        }
        for (Map.Entry<Integer, Integer> entry : spaces.codepoints().entrySet()) {
            widths.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(widths);
    }

    private static ResolvedGlyph resolve(String id, String image, int height, int ascent, String fallback,
                                         Integer declaredWidth, int frames, boolean overlay, String anchor,
                                         GlyphLedger ledger, ImageProbe probe) {
        List<Integer> codepoints = new ArrayList<>(frames);
        for (int frame = 0; frame < frames; frame++) {
            codepoints.add(ledger.codepointFor(frame == 0 ? id : id + "#" + frame));
        }
        return new ResolvedGlyph(id, image, height, ascent, fallback,
            advance(image, height, frames, declaredWidth, probe), frames, codepoints, overlay, anchor);
    }

    /**
     * The client advances by the glyph's scaled cell width plus the one pixel gap it draws between
     * glyphs. Without a readable image the height is the best guess available.
     */
    private static int advance(String image, int height, int frames, Integer declaredWidth, ImageProbe probe) {
        if (declaredWidth != null) {
            return declaredWidth;
        }
        int[] size = probe.size(image);
        if (size == null || size.length != 2 || size[0] <= 0 || size[1] <= 0) {
            return height + 1;
        }
        int cell = Math.max(1, size[0] / frames);
        return (int) Math.round(cell * height / (double) size[1]) + 1;
    }
}
