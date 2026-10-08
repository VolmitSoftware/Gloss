package art.arcane.gloss.importer;

import art.arcane.gloss.tab.TablistDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TablistSchemaConverter {
    private TablistSchemaConverter() {
    }

    public static Conversion convert(String id, JsonObject source) {
        Objects.requireNonNull(id, "id");
        JsonObject document = Objects.requireNonNull(source, "source").deepCopy();
        int version = integer(document.get("schemaVersion"), "schemaVersion");
        if (version != 2 && version != TablistDoc.CURRENT_SCHEMA_VERSION) {
            throw unsupported("Unsupported tablist schemaVersion " + version);
        }
        List<String> warnings = new ArrayList<>();
        try {
            if (version == 2) {
                JsonElement layout = document.get("layout");
                if (layout != null && !layout.isJsonNull()) {
                    convertLayout(layout.getAsJsonObject(), warnings);
                }
                document.addProperty("schemaVersion", TablistDoc.CURRENT_SCHEMA_VERSION);
            }
            TablistDoc.parse(id + ".json", document.toString());
            return new Conversion(document, warnings);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new VersionedGlossConverter.UnsupportedFormatException("Cannot convert tablist " + id + ": "
                + failure.getMessage(), failure);
        }
    }

    private static void convertLayout(JsonObject layout, List<String> warnings) {
        int columns = layout.has("columns") ? integer(layout.get("columns"), "layout.columns") : 0;
        int rows = layout.has("rows") ? integer(layout.get("rows"), "layout.rows") : 0;
        boolean enabled = layout.has("enabled") && layout.get("enabled").getAsBoolean();
        if ((enabled || columns != 0 || rows != 0) && (columns < 1 || columns > 4 || rows < 1 || rows > 20)) {
            throw unsupported("Existing tablist layout dimensions must be 1..4 columns and 1..20 rows");
        }
        int nativeRows = columns == 0 ? 1 : Math.max(rows, 20 * (columns - 1) / columns + 1);
        int entries = Math.max(1, columns * nativeRows);
        if (enabled && nativeRows != rows) {
            warnings.add("Approximate: layout " + columns + "x" + rows + " becomes " + columns + "x" + nativeRows
                + " (" + entries + " entries) because Minecraft derives its columns from the listed entry count; "
                + "authored slot coordinates are preserved and added cells remain blank.");
        }
        JsonArray sections = new JsonArray();
        JsonElement playersValue = layout.get("players");
        if (playersValue != null && !playersValue.isJsonNull()) {
            JsonObject players = playersValue.getAsJsonObject().deepCopy();
            players.addProperty("id", "players");
            players.addProperty("row", 0);
            int start = integer(players.get("column"), "layout.players.column");
            int width = players.has("columns") ? Math.max(1, integer(players.get("columns"), "layout.players.columns")) : 1;
            int height = players.has("rows") ? Math.max(1, integer(players.get("rows"), "layout.players.rows")) : 1;
            if (start < 0 || start + width > columns || height > rows) {
                throw unsupported("Existing tablist players block exceeds its authored grid");
            }
            players.addProperty("columns", width);
            players.addProperty("rows", height);
            sections.add(players);
            JsonArray slots = layout.has("slots") && !layout.get("slots").isJsonNull()
                ? layout.getAsJsonArray("slots") : new JsonArray();
            JsonArray visible = new JsonArray();
            for (JsonElement value : slots) {
                JsonObject slot = value.getAsJsonObject();
                int column = integer(slot.get("column"), "layout.slots.column");
                int row = integer(slot.get("row"), "layout.slots.row");
                if (column < start || column >= start + width || row < 0 || row >= height) {
                    visible.add(slot.deepCopy());
                }
            }
            layout.add("slots", visible);
        }
        layout.remove("columns");
        layout.remove("rows");
        layout.remove("players");
        layout.addProperty("entries", entries);
        layout.add("sections", sections);
    }

    private static int integer(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw unsupported(path + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new VersionedGlossConverter.UnsupportedFormatException(path + " must be an integer", failure);
        }
    }

    private static VersionedGlossConverter.UnsupportedFormatException unsupported(String message) {
        return new VersionedGlossConverter.UnsupportedFormatException(message);
    }

    public record Conversion(JsonObject document, List<String> warnings) {
        public Conversion {
            document = document.deepCopy();
            warnings = List.copyOf(warnings);
        }

        @Override
        public JsonObject document() {
            return document.deepCopy();
        }
    }
}
