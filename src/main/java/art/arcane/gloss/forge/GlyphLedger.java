package art.arcane.gloss.forge;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Private-use codepoints for glyph ids and space widths. An allocation is permanent: a glyph that
 * leaves every document keeps its number, so re-adding it later hands back the same character and
 * clients that cached the old pack still line up. Reallocation would silently repaint every chat
 * line and scoreboard that a player has already seen.
 */
public final class GlyphLedger {
    public static final int DEFAULT_BASE = 0xE000;
    public static final int MIN_BASE = 0xE000;
    public static final int MAX_CODEPOINT = 0xF8FF;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final int base;
    private final Map<String, Integer> glyphs;
    private final Map<Integer, Integer> spaces;
    private final Map<Integer, String> owners;
    private int deferred;
    private boolean dirty;

    private GlyphLedger(Path file, int base, Map<String, Integer> glyphs, Map<Integer, Integer> spaces) {
        this.file = file;
        this.base = base;
        this.glyphs = new TreeMap<>(glyphs);
        this.spaces = new TreeMap<>(spaces);
        this.owners = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : this.glyphs.entrySet()) {
            owners.put(entry.getValue(), "glyph " + entry.getKey());
        }
        for (Map.Entry<Integer, Integer> entry : this.spaces.entrySet()) {
            owners.put(entry.getValue(), "space " + entry.getKey());
        }
    }

    /**
     * Reads {@code forge/ledger.json}. A missing file starts an empty ledger; a file that will not
     * parse refuses, because starting empty means the first allocation atomically replaces the
     * only record of the previous assignment and every client holding the cached pack renders the
     * wrong image for every glyph. The file is left exactly as it is so the operator can repair
     * it, and reallocating from scratch stays something they have to ask for by deleting it.
     *
     * @throws IllegalStateException when the file exists and will not parse
     */
    public static GlyphLedger load(Path file, int base) {
        Objects.requireNonNull(file, "file");
        int resolvedBase = Math.clamp(base, MIN_BASE, MAX_CODEPOINT);
        Map<String, Integer> glyphs = new TreeMap<>();
        Map<Integer, Integer> spaces = new TreeMap<>();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                readGlyphs(root, glyphs);
                readSpaces(root, spaces);
            } catch (RuntimeException | IOException failure) {
                throw new IllegalStateException(file.getFileName()
                    + " will not parse (" + failure.getMessage() + "). Every glyph keeps the codepoint"
                    + " this file gave it, so the pack is not rebuilt until the file is repaired;"
                    + " deleting it renumbers every glyph and repaints every message already sent.",
                    failure);
            }
        }
        return new GlyphLedger(file, resolvedBase, glyphs, spaces);
    }

    /**
     * Runs {@code work} with writes deferred, so building a whole registry costs one file write
     * instead of one per codepoint. Nested calls collapse into the outermost.
     */
    public synchronized void batch(Runnable work) {
        Objects.requireNonNull(work, "work");
        deferred++;
        try {
            work.run();
        } finally {
            deferred--;
            if (deferred == 0 && dirty) {
                dirty = false;
                save();
            }
        }
    }

    public int base() {
        return base;
    }

    /** The codepoint for a glyph id, allocating and persisting one when the id is new. */
    public synchronized int codepointFor(String glyphId) {
        String id = Objects.requireNonNull(glyphId, "glyphId");
        Integer known = glyphs.get(id);
        if (known != null) {
            return known;
        }
        int codepoint = allocate("glyph " + id);
        glyphs.put(id, codepoint);
        save();
        return codepoint;
    }

    /** The codepoint for a space advance, allocating and persisting one when the width is new. */
    public synchronized int spaceCodepoint(int widthPx) {
        Integer known = spaces.get(widthPx);
        if (known != null) {
            return known;
        }
        int codepoint = allocate("space " + widthPx);
        spaces.put(widthPx, codepoint);
        save();
        return codepoint;
    }

    public synchronized Map<String, Integer> glyphs() {
        return Map.copyOf(glyphs);
    }

    public synchronized Map<Integer, Integer> spaces() {
        return Map.copyOf(spaces);
    }

    public synchronized int size() {
        return glyphs.size() + spaces.size();
    }

    private int allocate(String owner) {
        for (int codepoint = base; codepoint <= MAX_CODEPOINT; codepoint++) {
            if (!owners.containsKey(codepoint)) {
                owners.put(codepoint, owner);
                return codepoint;
            }
        }
        throw new IllegalStateException("forge: the private-use range from U+"
            + Integer.toHexString(base).toUpperCase(Locale.ROOT) + " to U+F8FF is full");
    }

    private void save() {
        if (deferred > 0) {
            dirty = true;
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("base", base);
        JsonObject glyphObject = new JsonObject();
        for (Map.Entry<String, Integer> entry : glyphs.entrySet()) {
            glyphObject.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("glyphs", glyphObject);
        JsonObject spaceObject = new JsonObject();
        for (Map.Entry<Integer, Integer> entry : spaces.entrySet()) {
            spaceObject.addProperty(Integer.toString(entry.getKey()), entry.getValue());
        }
        root.add("spaces", spaceObject);
        try {
            AtomicFiles.createParentDirectories(file);
            AtomicFiles.replace(file, (GSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            Gloss.logExceptionStack(true, failure, "forge: %s could not be written.", file.getFileName());
        }
    }

    private static void readGlyphs(JsonObject root, Map<String, Integer> into) {
        JsonElement element = root.get("glyphs");
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            into.put(entry.getKey(), entry.getValue().getAsInt());
        }
    }

    private static void readSpaces(JsonObject root, Map<Integer, Integer> into) {
        JsonElement element = root.get("spaces");
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            into.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
        }
    }
}
