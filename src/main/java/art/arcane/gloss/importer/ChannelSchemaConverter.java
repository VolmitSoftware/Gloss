package art.arcane.gloss.importer;

import art.arcane.gloss.chat.ChannelDoc;
import art.arcane.gloss.doc.DocumentParsers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChannelSchemaConverter {
    private ChannelSchemaConverter() {
    }

    public static Conversion convert(String id, JsonObject source) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
        JsonObject document = source.deepCopy();
        List<String> warnings = new ArrayList<>();
        int version = version(document);
        if (version != 1 && version != ChannelDoc.CURRENT_SCHEMA_VERSION) {
            throw new VersionedGlossConverter.UnsupportedFormatException("Unsupported channels schemaVersion " + version);
        }
        if (version == 1) {
            boolean filtered = inspectFilters(document, "filters", warnings);
            if (document.has("variants")) {
                JsonArray variants = document.getAsJsonArray("variants");
                for (int index = 0; index < variants.size(); index++) {
                    filtered |= inspectFilters(variants.get(index).getAsJsonObject(), "variants[" + index + "].filters", warnings);
                }
            }
            if (filtered) {
                warnings.add("Approximate: channel filters now enforce finite input, output, match, work and elapsed budgets; "
                    + "review filtering limits and the default drop policy before applying.");
            }
            document.addProperty("schemaVersion", ChannelDoc.CURRENT_SCHEMA_VERSION);
            JsonObject filtering = new JsonObject();
            filtering.addProperty("syntax", "re2");
            document.add("filtering", filtering);
        }
        try {
            ChannelDoc parsed = ChannelDoc.parse(id + ".json", document.toString());
            return new Conversion(version == ChannelDoc.CURRENT_SCHEMA_VERSION ? document
                : DocumentParsers.GSON.toJsonTree(parsed).getAsJsonObject(), warnings);
        } catch (IllegalArgumentException failure) {
            throw new VersionedGlossConverter.UnsupportedFormatException("Cannot convert channel " + id + ": "
                + failure.getMessage() + ". Rewrite unsupported filters in RE2 syntax or adjust declared limits before import.", failure);
        }
    }

    private static int version(JsonObject source) {
        JsonElement value = source.get("schemaVersion");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new VersionedGlossConverter.UnsupportedFormatException("Unsupported schemaVersion: expected an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new VersionedGlossConverter.UnsupportedFormatException("Unsupported schemaVersion: expected an integer", failure);
        }
    }

    private static boolean inspectFilters(JsonObject document, String path, List<String> warnings) {
        if (!document.has("filters") || document.get("filters").isJsonNull()) {
            return false;
        }
        JsonArray filters = document.getAsJsonArray("filters");
        for (int index = 0; index < filters.size(); index++) {
            JsonElement value = filters.get(index);
            if (!value.isJsonObject() || !value.getAsJsonObject().has("match")) {
                throw new VersionedGlossConverter.UnsupportedFormatException(path + "[" + index + "] requires match");
            }
            String pattern = value.getAsJsonObject().get("match").getAsString();
            if (!exactLiteral(pattern)) {
                warnings.add("Approximate: " + path + "[" + index + "] uses regex semantics outside the verified ASCII literal subset; "
                    + "review RE2 flags, Unicode, character classes and boundaries. Replacement remains literal.");
            }
        }
        return !filters.isEmpty();
    }

    private static boolean exactLiteral(String pattern) {
        for (int index = 0; index < pattern.length(); index++) {
            char value = pattern.charAt(index);
            if (value < 32 || value > 126 || "\\.^$|?*+()[]{}".indexOf(value) >= 0) {
                return false;
            }
        }
        return !pattern.isEmpty();
    }

    public record Conversion(JsonObject document, List<String> warnings) {
        public Conversion {
            document = Objects.requireNonNull(document, "document");
            warnings = List.copyOf(warnings);
        }
    }
}
