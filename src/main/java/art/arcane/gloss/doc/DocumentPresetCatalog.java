package art.arcane.gloss.doc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

public final class DocumentPresetCatalog {
    public static final String FILE_NAME = "presets.json";
    public static final String KIND = "presets";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_COMPILED_WEIGHT = 64L * 1024L * 1024L;
    private static final int MAX_DEPTH = 128;
    private static final Set<String> DOCUMENT_KINDS = Set.of("animations", "bubbles", "previews",
        "damage-indicators", "emoji", "entity-overlays", "holograms", "menus", "names", "motd",
        "panels", "real-drops", "boards", "tablist", "surfaces", "nametags", "channels", "strings",
        "leaderboards", "inventories", "markers", "nameplates", "waypoints", "behaviors", "glyphs",
        "connections");
    private static final Set<String> IDENTITY_FIELDS = Set.of("schemaVersion", "revision", "id", "uuid", "preset");
    private static final Set<String> CATALOG_FIELDS = Set.of("schemaVersion", "revision", "defaults", "presets");
    private static final Set<String> PRESET_FIELDS = Set.of("extends", "values");
    private static final DocumentPresetCatalog EMPTY = new DocumentPresetCatalog(1L, Map.of(), Map.of(),
        "{\"schemaVersion\":1,\"revision\":1}");

    private final long revision;
    private final Map<String, JsonObject> defaults;
    private final Map<String, Map<String, JsonObject>> presets;
    private final String source;

    private DocumentPresetCatalog(long revision, Map<String, JsonObject> defaults,
                                  Map<String, Map<String, JsonObject>> presets, String source) {
        this.revision = revision;
        this.defaults = Map.copyOf(defaults);
        this.presets = Map.copyOf(presets);
        this.source = source;
    }

    public static DocumentPresetCatalog empty() {
        return EMPTY;
    }

    public static DocumentPresetCatalog read(Path dataDirectory) throws IOException {
        String source = readSource(dataDirectory);
        return source == null ? empty() : parse(FILE_NAME, source);
    }

    static String readSource(Path root) throws IOException {
        Path file = root.resolve(FILE_NAME);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Preset catalog must be a regular file: " + file);
        }
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new IOException("Preset catalog exceeds " + MAX_SOURCE_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static DocumentPresetCatalog parse(String fileName, String source) {
        if (source.length() > MAX_SOURCE_BYTES || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Preset catalog exceeds " + MAX_SOURCE_BYTES + " bytes");
        }
        JsonObject root = object(JsonParser.parseString(source), fileName);
        weight(root);
        rejectUnknown(root, CATALOG_FIELDS, fileName);
        int schema = number(root, "schemaVersion").intValueExact();
        DocumentEnvelope.requireSchemaVersion(KIND, schema, SCHEMA_VERSION);
        long revision = DocumentEnvelope.requireRevision(KIND, number(root, "revision").longValueExact());
        Map<String, JsonObject> defaults = new LinkedHashMap<>();
        CompilationBudget budget = new CompilationBudget();
        for (Map.Entry<String, JsonElement> entry : optionalObject(root, "defaults").entrySet()) {
            requireKind(entry.getKey());
            JsonObject values = object(entry.getValue(), "defaults." + entry.getKey());
            requireValues(values, "defaults." + entry.getKey());
            budget.reserve(values);
            defaults.put(entry.getKey(), values.deepCopy());
        }
        Map<String, Map<String, JsonObject>> presets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : optionalObject(root, "presets").entrySet()) {
            requireKind(entry.getKey());
            JsonObject definitions = object(entry.getValue(), "presets." + entry.getKey());
            presets.put(entry.getKey(), compile(entry.getKey(), definitions, budget));
        }
        return new DocumentPresetCatalog(revision, defaults, presets, source);
    }

    public long revision() {
        return revision;
    }

    public JsonObject document() {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    public static boolean supports(String kind) {
        return DOCUMENT_KINDS.contains(kind);
    }

    public Set<String> kinds() {
        Set<String> kinds = new HashSet<>(defaults.keySet());
        kinds.addAll(presets.keySet());
        return Set.copyOf(kinds);
    }

    public String resolve(String kind, String source) {
        if (KIND.equals(kind)) {
            return source;
        }
        if (!source.stripLeading().startsWith("{") && !defaults.containsKey(kind)) {
            return source;
        }
        JsonElement parsed = JsonParser.parseString(source);
        if (!parsed.isJsonObject()) {
            if (defaults.containsKey(kind)) {
                throw new IllegalArgumentException(kind + " defaults require an object document");
            }
            return source;
        }
        JsonObject document = parsed.getAsJsonObject();
        weight(document);
        JsonElement selected = document.get("preset");
        JsonObject base = defaults.get(kind);
        if (selected == null && base == null) {
            return source;
        }
        JsonObject resolved = base == null ? new JsonObject() : base.deepCopy();
        if (selected != null && !selected.isJsonNull()) {
            String name = string(selected, "preset");
            JsonObject preset = presets.getOrDefault(kind, Map.of()).get(name);
            if (preset == null) {
                throw new IllegalArgumentException(kind + " preset does not exist: " + name);
            }
            merge(resolved, preset);
        }
        merge(resolved, document);
        resolved.remove("preset");
        return resolved.toString();
    }

    public boolean applies(String kind, String source) {
        if (defaults.containsKey(kind)) {
            return true;
        }
        JsonObject document = object(JsonParser.parseString(source), kind);
        JsonElement selected = document.get("preset");
        return selected != null && !selected.isJsonNull();
    }

    public static JsonObject applyEdits(JsonObject authored, JsonObject before, JsonObject after) {
        JsonObject result = authored.deepCopy();
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            JsonElement previous = before.get(key);
            JsonElement next = after.get(key);
            if (Objects.equals(previous, next)) {
                continue;
            }
            if (next == null) {
                result.add(key, JsonNull.INSTANCE);
            } else if (previous != null && previous.isJsonObject() && next.isJsonObject()) {
                JsonElement existing = authored.get(key);
                result.add(key, applyEdits(existing != null && existing.isJsonObject()
                    ? existing.getAsJsonObject() : new JsonObject(), previous.getAsJsonObject(), next.getAsJsonObject()));
            } else {
                result.add(key, next.deepCopy());
            }
        }
        return result;
    }

    private static Map<String, JsonObject> compile(String kind, JsonObject definitions, CompilationBudget budget) {
        Map<String, JsonObject> compiled = new HashMap<>();
        for (String name : definitions.keySet()) {
            requireKey(name, "preset name");
            if (compiled.containsKey(name)) {
                continue;
            }
            List<String> chain = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String cursor = name;
            while (cursor != null && !compiled.containsKey(cursor)) {
                if (!visited.add(cursor)) {
                    throw new IllegalArgumentException(kind + " preset inheritance cycle: " + cursor);
                }
                JsonObject definition = object(definitions.get(cursor), kind + " preset " + cursor);
                rejectUnknown(definition, PRESET_FIELDS, kind + " preset " + cursor);
                JsonObject values = optionalObject(definition, "values");
                requireValues(values, kind + " preset " + cursor);
                chain.add(cursor);
                JsonElement parent = definition.get("extends");
                cursor = parent == null || parent.isJsonNull() ? null : string(parent, "extends");
            }
            JsonObject inherited = cursor == null ? new JsonObject() : compiled.get(cursor).deepCopy();
            for (int index = chain.size() - 1; index >= 0; index--) {
                String current = chain.get(index);
                merge(inherited, optionalObject(definitions.getAsJsonObject(current), "values"));
                budget.reserve(inherited);
                compiled.put(current, inherited.deepCopy());
            }
        }
        return Map.copyOf(compiled);
    }

    private static void merge(JsonObject target, JsonObject overlay) {
        for (Map.Entry<String, JsonElement> entry : overlay.entrySet()) {
            JsonElement previous = target.get(entry.getKey());
            if (previous != null && previous.isJsonObject() && entry.getValue().isJsonObject()) {
                merge(previous.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                target.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static void requireValues(JsonObject values, String path) {
        for (String field : IDENTITY_FIELDS) {
            if (values.has(field)) {
                throw new IllegalArgumentException(path + " cannot define " + field);
            }
        }
    }

    private static void requireKey(String key, String label) {
        if (key.isBlank() || key.contains("/") || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + key);
        }
    }

    private static void requireKind(String kind) {
        if (!supports(kind)) {
            throw new IllegalArgumentException("Unknown preset document kind: " + kind);
        }
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonObject optionalObject(JsonObject source, String key) {
        return source.has(key) ? object(source.get(key), key) : new JsonObject();
    }

    private static String string(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(path + " must be a nonblank string");
        }
        return value.getAsString();
    }

    private static BigDecimal number(JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        return value.getAsBigDecimal();
    }

    private static void rejectUnknown(JsonObject source, Set<String> allowed, String path) {
        for (String field : source.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " has unknown field: " + field);
            }
        }
    }

    private static long weight(JsonElement root) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(root, 0));
        long weight = 0L;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (node.depth() > MAX_DEPTH) {
                throw new IllegalArgumentException("Preset values exceed " + MAX_DEPTH + " nested levels");
            }
            weight += 64L;
            JsonElement value = node.value();
            if (value.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                    weight += 64L + 2L * entry.getKey().length();
                    pending.add(new Node(entry.getValue(), node.depth() + 1));
                }
            } else if (value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    pending.add(new Node(element, node.depth() + 1));
                }
            } else if (value.isJsonPrimitive()) {
                weight += 2L * value.getAsString().length();
            }
            if (weight > MAX_COMPILED_WEIGHT) {
                throw new IllegalArgumentException("Preset values exceed the prepared-data memory limit");
            }
        }
        return weight;
    }

    private record Node(JsonElement value, int depth) {
    }

    private static final class CompilationBudget {
        private long remaining = MAX_COMPILED_WEIGHT;

        void reserve(JsonElement value) {
            remaining -= weight(value);
            if (remaining < 0L) {
                throw new IllegalArgumentException("Preset catalog exceeds the prepared-data memory limit");
            }
        }
    }
}
