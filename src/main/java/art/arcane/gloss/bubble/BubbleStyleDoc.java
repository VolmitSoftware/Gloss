package art.arcane.gloss.bubble;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record BubbleStyleDoc(int schemaVersion, long revision, String prefix, Vector offset, int wordWrapChars,
                             int lineStaggerTicks, long maxAliveMs, boolean flyAway, boolean followPlayer,
                             boolean hideOwn, Select select) {
    public static final String KIND = "bubbles";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final BubbleStyleDoc DEFAULTS = new BubbleStyleDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, "&7", new Vector(0.0D, 1.0D, 0.0D), 32, 5, 5000L, true, true, true, null);

    public BubbleStyleDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        prefix = prefix == null ? "&7" : prefix;
        offset = offset == null ? new Vector(0.0D, 1.0D, 0.0D) : offset.clone();
        wordWrapChars = clampInt(wordWrapChars, 8, 128);
        lineStaggerTicks = clampInt(lineStaggerTicks, 0, 40);
        maxAliveMs = Math.max(500L, Math.min(60000L, maxAliveMs));
    }

    public static BubbleStyleDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, BubbleStyleDoc.class);
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Select(List<String> worlds, List<String> groups, int priority) {
        public Select {
            worlds = cleanStrings(worlds, false);
            groups = cleanStrings(groups, true);
        }

        private static List<String> cleanStrings(List<String> values, boolean lowercase) {
            if (values == null) {
                return List.of();
            }
            List<String> cleaned = new ArrayList<>(values.size());
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = value.trim();
                cleaned.add(lowercase ? normalized.toLowerCase(Locale.ROOT) : normalized);
            }
            return List.copyOf(cleaned);
        }
    }
}
