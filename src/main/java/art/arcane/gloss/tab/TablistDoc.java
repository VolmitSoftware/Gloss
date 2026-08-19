package art.arcane.gloss.tab;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record TablistDoc(int schemaVersion, long revision, boolean useHeaderFooter, String header, String footer,
                         boolean groupListNames, Map<String, String> nameFormats) {
    public static final String KIND = "tablist";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String OP_GROUP_KEY = "_op";
    public static final String DEFAULT_GROUP_KEY = "default";
    public static final String FALLBACK_FORMAT = "$player";

    public static final TablistDoc DEFAULTS = new TablistDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, true, "&d&lGloss", "&7VolmitSoftware.com", true,
        Map.of(DEFAULT_GROUP_KEY, FALLBACK_FORMAT, OP_GROUP_KEY, "&6$player"));

    public TablistDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        header = header == null ? "" : header;
        footer = footer == null ? "" : footer;
        nameFormats = copyFormats(nameFormats);
    }

    public static TablistDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, TablistDoc.class);
    }

    private static Map<String, String> copyFormats(Map<String, String> formats) {
        if (formats == null) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>(formats.size());
        for (Map.Entry<String, String> entry : formats.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            copied.put(key.trim().toLowerCase(Locale.ROOT), entry.getValue() == null ? "" : entry.getValue());
        }
        return Map.copyOf(copied);
    }
}
