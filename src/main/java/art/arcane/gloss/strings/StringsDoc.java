package art.arcane.gloss.strings;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One authored content catalog, addressed by locale. Operators translate the strings their own
 * documents reference; the plugin's own message catalog stays in {@code languages/*.toml}.
 */
public record StringsDoc(int schemaVersion, long revision, String locale, String fallback,
                         Map<String, String> entries) {
    public static final String KIND = "strings";
    public static final String DEFAULT_ID = "en_US";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_VALUE_LENGTH = 4096;
    public static final int MAX_ENTRIES = 8192;

    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2}[_-][A-Za-z]{2}");
    private static final Pattern ENTRY_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public StringsDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        locale = requireLocale(locale);
        fallback = optionalLocale(fallback);
        entries = copyEntries(entries);
    }

    public static StringsDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, StringsDoc.class);
    }

    /** {@code de-de} and {@code DE_de} both canonicalize to {@code de_DE}; anything else is null. */
    public static String canonicalLocale(String value) {
        if (value == null || !LOCALE.matcher(value).matches()) {
            return null;
        }
        return value.substring(0, 2).toLowerCase(Locale.ROOT) + "_"
            + value.substring(3).toUpperCase(Locale.ROOT);
    }

    private static String requireLocale(String value) {
        String canonical = canonicalLocale(value);
        if (canonical == null) {
            throw new IllegalArgumentException("strings locale must be language_COUNTRY: " + value);
        }
        return canonical;
    }

    private static String optionalLocale(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String canonical = canonicalLocale(value);
        if (canonical == null) {
            throw new IllegalArgumentException("strings fallback must be language_COUNTRY: " + value);
        }
        return canonical;
    }

    private static Map<String, String> copyEntries(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("strings documents may declare at most " + MAX_ENTRIES + " entries");
        }
        Map<String, String> copied = new LinkedHashMap<>(entries.size());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (key == null || !ENTRY_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("strings key must match [a-z0-9][a-z0-9._-]*: " + key);
            }
            String value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("strings value must not be null: " + key);
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("strings value exceeds " + MAX_VALUE_LENGTH
                    + " characters: " + key);
            }
            copied.put(key, value);
        }
        return Map.copyOf(copied);
    }
}
