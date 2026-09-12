package art.arcane.gloss.forge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Advance widths of the vanilla default font in pixels, including the one pixel glyph gap. Legacy
 * colour codes and MiniMessage tags are zero width; bold adds one pixel per glyph until a reset or
 * colour code; codepoints declared by glyph documents override the table.
 */
public final class FontMetrics {
    private static final String RESOURCE = "/forge/default-font-widths.json";
    private static final String LEGACY_CODES = "0123456789abcdefklmnor";
    private static final String COLOR_CODES = "0123456789abcdef";

    private final String version;
    private final int defaultWidth;
    private final Map<Integer, Integer> table;
    private volatile Map<Integer, Integer> declared;

    private FontMetrics(String version, int defaultWidth, Map<Integer, Integer> table) {
        this.version = version;
        this.defaultWidth = defaultWidth;
        this.table = Map.copyOf(table);
        this.declared = Map.of();
    }

    public static FontMetrics load() {
        try (InputStream stream = FontMetrics.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonObject root = new Gson().fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            String version = root.get("version").getAsString();
            int defaultWidth = root.get("defaultWidth").getAsInt();
            Map<Integer, Integer> table = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("widths").entrySet()) {
                table.put(entry.getKey().codePointAt(0), entry.getValue().getAsInt());
            }
            return new FontMetrics(version, defaultWidth, table);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public String version() {
        return version;
    }

    public int defaultWidth() {
        return defaultWidth;
    }

    public boolean declared(int codepoint) {
        return table.containsKey(codepoint) || declared.containsKey(codepoint);
    }

    public int width(int codepoint) {
        Integer custom = declared.get(codepoint);
        if (custom != null) {
            return custom;
        }
        Integer known = table.get(codepoint);
        return known != null ? known : defaultWidth;
    }

    public int width(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        boolean bold = false;
        int index = 0;
        int length = text.length();
        while (index < length) {
            char marker = text.charAt(index);
            if (marker == '<') {
                int close = text.indexOf('>', index + 1);
                if (close > index && isTag(text, index + 1, close)) {
                    bold = boldAfterTag(text.substring(index + 1, close), bold);
                    index = close + 1;
                    continue;
                }
            }
            int formatting = formattingLength(text, index);
            if (formatting > 0) {
                char code = Character.toLowerCase(text.charAt(index + 1));
                if (formatting == 2) {
                    if (code == 'l') {
                        bold = true;
                    } else if (code == 'r' || COLOR_CODES.indexOf(code) >= 0) {
                        bold = false;
                    }
                } else {
                    bold = false;
                }
                index += formatting;
                continue;
            }
            int codepoint = text.codePointAt(index);
            total += width(codepoint) + (bold ? 1 : 0);
            index += Character.charCount(codepoint);
        }
        return total;
    }

    public synchronized void declare(int codepoint, int width) {
        Map<Integer, Integer> next = new HashMap<>(declared);
        next.put(codepoint, width);
        declared = Map.copyOf(next);
    }

    public synchronized void replaceDeclared(Map<Integer, Integer> widths) {
        declared = Map.copyOf(Objects.requireNonNull(widths, "widths"));
    }

    private static boolean isTag(String text, int start, int end) {
        if (start >= end) {
            return false;
        }
        char first = text.charAt(start);
        if (first == '/') {
            return end - start > 1 && Character.isLetter(text.charAt(start + 1));
        }
        return Character.isLetter(first) || first == '#' || first == '!' || first == '?';
    }

    private static boolean boldAfterTag(String tag, boolean bold) {
        String name = tag;
        int colon = name.indexOf(':');
        if (colon > 0) {
            name = name.substring(0, colon);
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "bold", "b" -> true;
            case "/bold", "/b", "reset", "/reset" -> false;
            default -> bold;
        };
    }

    private static int formattingLength(String value, int index) {
        char marker = value.charAt(index);
        if ((marker != '&' && marker != '§') || index + 1 >= value.length()) {
            return 0;
        }
        char code = Character.toLowerCase(value.charAt(index + 1));
        if (LEGACY_CODES.indexOf(code) >= 0) {
            return 2;
        }
        if (value.charAt(index + 1) == '#' && index + 8 <= value.length() && hex(value, index + 2, 6)) {
            return 8;
        }
        if (code == 'x' && index + 14 <= value.length()) {
            for (int offset = 2; offset < 14; offset += 2) {
                if (value.charAt(index + offset) != marker
                    || Character.digit(value.charAt(index + offset + 1), 16) < 0) {
                    return 0;
                }
            }
            return 14;
        }
        return 0;
    }

    private static boolean hex(String value, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
