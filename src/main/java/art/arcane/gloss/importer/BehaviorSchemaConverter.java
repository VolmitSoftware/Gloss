package art.arcane.gloss.importer;

import art.arcane.gloss.behavior.BehaviorDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class BehaviorSchemaConverter {
    private BehaviorSchemaConverter() {
    }

    public static VersionedGlossConverter.Conversion convert(String id, JsonObject source) {
        JsonObject document = source.deepCopy();
        List<String> warnings = new ArrayList<>();
        if (source.get("schemaVersion").getAsInt() == 1) {
            JsonArray entries = document.has("on") && !document.get("on").isJsonNull()
                ? document.getAsJsonArray("on") : new JsonArray();
            boolean chat = false;
            for (int index = 0; index < entries.size(); index++) {
                JsonObject entry = entries.get(index).getAsJsonObject();
                if (!entry.has("trigger") || !entry.get("trigger").getAsString().equals("chat")) {
                    continue;
                }
                chat = true;
                JsonElement pattern = entry.get("pattern");
                if (pattern != null && !pattern.isJsonNull() && !exactLiteral(pattern.getAsString())) {
                    warnings.add("Approximate: on[" + index + "].pattern changes from Java regex to RE2; "
                        + "review flags, Unicode, character classes and boundaries before applying.");
                }
            }
            if (chat) {
                warnings.add("Approximate: chat behavior matching now enforces finite input and per-document/shared "
                    + "work budgets. Limit exhaustion skips behavior matching without dropping ordinary chat.");
            }
            document.addProperty("schemaVersion", BehaviorDoc.CURRENT_SCHEMA_VERSION);
            JsonObject matching = new JsonObject();
            matching.addProperty("syntax", "re2");
            document.add("matching", matching);
        }
        try {
            BehaviorDoc.parse(id + ".json", document.toString());
        } catch (IllegalArgumentException failure) {
            throw new VersionedGlossConverter.UnsupportedFormatException("Cannot convert behavior " + id + ": "
                + failure.getMessage() + ". Rewrite unsupported patterns before import; the original is retained.", failure);
        }
        return new VersionedGlossConverter.Conversion(document, warnings);
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
}
