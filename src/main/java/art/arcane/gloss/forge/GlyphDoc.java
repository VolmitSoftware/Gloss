package art.arcane.gloss.forge;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A {@code glyphs/} document: the bitmaps the pack build turns into font providers, the emoji they
 * stand in for, and the negative-space range the layout functions shift with. Ids are stable names
 * an author writes in {@code glyph(id)}; the codepoint behind an id lives in the ledger, never here,
 * so a document can be reordered or rewritten without renumbering anything.
 */
public record GlyphDoc(int schemaVersion, long revision, String namespace, String font,
                       List<Glyph> glyphs, Space space, List<Overlay> overlays) {
    public static final String KIND = "glyphs";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String DEFAULT_NAMESPACE = "gloss";
    public static final String DEFAULT_FONT = "glyphs";
    public static final String DEFAULT_ID = "space";
    public static final int DEFAULT_HEIGHT = 8;
    public static final int MAX_HEIGHT = 256;
    public static final int MAX_FRAMES = 256;
    public static final int SPACE_LIMIT = 256;
    public static final int MAX_GLYPHS = 1024;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern IMAGE_PATH = Pattern.compile("[A-Za-z0-9_.\\-/]+");
    private static final int MAX_ID_LENGTH = 64;

    /** The envelope an empty document carries; the pattern constants above must initialise first. */
    public static final GlyphDoc DEFAULTS =
        new GlyphDoc(CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, null, null, null, null, null);

    public GlyphDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        namespace = resourceName("namespace", namespace, DEFAULT_NAMESPACE);
        font = resourceName("font", font, DEFAULT_FONT);
        glyphs = glyphs == null ? List.of() : List.copyOf(glyphs);
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
        space = space == null ? Space.DEFAULT : space;
        requireDistinctIds(glyphs, overlays);
    }

    public static GlyphDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, GlyphDoc.class);
    }

    /** Every declared id in this document, glyphs first then overlays, in declaration order. */
    public List<String> declaredIds() {
        List<String> ids = new ArrayList<>(glyphs.size() + overlays.size());
        for (Glyph glyph : glyphs) {
            ids.add(glyph.id());
        }
        for (Overlay overlay : overlays) {
            ids.add(overlay.id());
        }
        return List.copyOf(ids);
    }

    private static void requireDistinctIds(List<Glyph> glyphs, List<Overlay> overlays) {
        if (glyphs.size() + overlays.size() > MAX_GLYPHS) {
            throw new IllegalArgumentException(KIND + " documents may declare at most " + MAX_GLYPHS
                + " glyphs and overlays");
        }
        Set<String> seen = new HashSet<>(glyphs.size() + overlays.size());
        for (Glyph glyph : glyphs) {
            Objects.requireNonNull(glyph, "glyph entries must not be null");
            requireUnique(seen, glyph.id());
        }
        for (Overlay overlay : overlays) {
            Objects.requireNonNull(overlay, "overlay entries must not be null");
            requireUnique(seen, overlay.id());
        }
    }

    private static void requireUnique(Set<String> seen, String id) {
        if (!seen.add(id)) {
            throw new IllegalArgumentException("duplicate glyph id: " + id);
        }
    }

    private static String resourceName(String field, String value, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!RESOURCE_NAME.matcher(resolved).matches()) {
            throw new IllegalArgumentException(field + " must match [a-z0-9_.-]+: " + resolved);
        }
        return resolved;
    }

    static String requireId(String value) {
        String id = Objects.requireNonNull(value, "glyph id").trim();
        if (id.length() > MAX_ID_LENGTH || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("glyph id must match [a-z0-9][a-z0-9_-]* and be at most "
                + MAX_ID_LENGTH + " characters: " + id);
        }
        return id;
    }

    /**
     * Image paths are relative to the plugin's {@code images/} folder, so they may not climb out of
     * it, name an absolute location or carry a Windows drive letter.
     */
    static String requireImage(String value) {
        String image = Objects.requireNonNull(value, "glyph image").trim().replace('\\', '/');
        if (image.isEmpty() || !IMAGE_PATH.matcher(image).matches()) {
            throw new IllegalArgumentException("glyph image must be a relative path under images/: " + image);
        }
        if (image.startsWith("/") || image.contains("..") || image.contains("//") || image.contains(":")) {
            throw new IllegalArgumentException("glyph image must stay inside images/: " + image);
        }
        if (!image.toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IllegalArgumentException("glyph image must be a .png file: " + image);
        }
        return image;
    }

    static int requireHeight(Integer value) {
        int height = value == null ? DEFAULT_HEIGHT : value;
        if (height < 1 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("glyph height must be between 1 and " + MAX_HEIGHT + ": " + height);
        }
        return height;
    }

    static int requireAscent(Integer value, int height) {
        int ascent = value == null ? Math.max(0, height - 1) : value;
        if (ascent > height) {
            throw new IllegalArgumentException("glyph ascent must not exceed its height: " + ascent + " > " + height);
        }
        return ascent;
    }

    /**
     * One bitmap. {@code width} overrides the advance the font metrics report for the glyph;
     * {@code frames} splits the image into that many equal-width cells for meters.
     */
    public record Glyph(String id, String image, Integer height, Integer ascent, String emoji,
                        String fallback, Integer width, Integer frames) {
        public Glyph {
            id = requireId(id);
            image = requireImage(image);
            height = requireHeight(height);
            ascent = requireAscent(ascent, height);
            emoji = emoji == null || emoji.isBlank() ? null : emoji.trim();
            fallback = fallback == null ? "" : fallback;
            if (width != null && (width < 0 || width > MAX_HEIGHT * 4)) {
                throw new IllegalArgumentException("glyph width must be between 0 and " + (MAX_HEIGHT * 4)
                    + ": " + width);
            }
            frames = frames == null ? 1 : frames;
            if (frames < 1 || frames > MAX_FRAMES) {
                throw new IllegalArgumentException("glyph frames must be between 1 and " + MAX_FRAMES + ": " + frames);
            }
        }
    }

    /** A bitmap authors place with {@code overlay(id)}; {@code anchor} says where on the line it sits. */
    public record Overlay(String id, String image, Integer height, Integer ascent, String anchor) {
        public static final Set<String> ANCHORS = Set.of("top", "center", "bottom");

        public Overlay {
            id = requireId(id);
            image = requireImage(image);
            height = requireHeight(height);
            ascent = requireAscent(ascent, height);
            anchor = anchor == null || anchor.isBlank() ? "center" : anchor.trim().toLowerCase(Locale.ROOT);
            if (!ANCHORS.contains(anchor)) {
                throw new IllegalArgumentException("overlay anchor must be top, center or bottom: " + anchor);
            }
        }
    }

    /** The negative-space provider range, in pixels, that {@code shift(px)} draws from. */
    public record Space(Boolean enabled, List<Integer> range) {
        public static final Space DEFAULT = new Space(null, null);

        public Space {
            enabled = enabled == null || enabled;
            range = range == null || range.isEmpty() ? List.of(-SPACE_LIMIT, SPACE_LIMIT) : List.copyOf(range);
            if (range.size() != 2 || range.get(0) == null || range.get(1) == null) {
                throw new IllegalArgumentException("space range must be [minimum, maximum]");
            }
            if (range.get(0) > range.get(1)) {
                throw new IllegalArgumentException("space range minimum must not exceed its maximum: " + range);
            }
            if (range.get(0) < -SPACE_LIMIT || range.get(1) > SPACE_LIMIT) {
                throw new IllegalArgumentException("space range must stay within -" + SPACE_LIMIT + ".."
                    + SPACE_LIMIT + ": " + range);
            }
        }

        public int minimum() {
            return range.get(0);
        }

        public int maximum() {
            return range.get(1);
        }
    }
}
