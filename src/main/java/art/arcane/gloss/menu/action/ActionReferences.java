package art.arcane.gloss.menu.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ActionReferences {
    private static final int MAX_EXPANDED_NODES = 100000;
    private static final int MAX_CALL_DEPTH = 32;
    private static final Set<String> ACTION_LISTS = Set.of("actions", "trueActions", "falseActions", "then",
        "else", "unsupported", "onTimeout");

    private ActionReferences() {
    }

    public static String resolve(String source) {
        JsonElement parsed = JsonParser.parseString(source);
        if (!parsed.isJsonObject()) {
            return source;
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement declared = root.get("actions");
        if (declared == null) {
            return source;
        }
        if (!declared.isJsonObject() || declared.getAsJsonObject().size() > 256) {
            throw new IllegalArgumentException("document actions must be an object containing at most 256 named action lists");
        }
        Map<String, JsonArray> library = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : declared.getAsJsonObject().entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}") || !entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException("named action requires a 1..64 character identifier and an action array: " + entry.getKey());
            }
            library.put(entry.getKey(), entry.getValue().getAsJsonArray());
        }
        Expansion expansion = new Expansion(library);
        for (String name : library.keySet()) {
            expansion.reference(name);
        }
        JsonObject effective = root.deepCopy();
        effective.remove("actions");
        return expansion.walk(effective, false, 0).toString();
    }

    private static final class Expansion {
        private final Map<String, JsonArray> library;
        private final Map<String, JsonArray> compiled = new LinkedHashMap<>();
        private final Map<String, Integer> depths = new LinkedHashMap<>();
        private final Deque<String> active = new ArrayDeque<>();
        private int nodes;

        private Expansion(Map<String, JsonArray> library) {
            this.library = library;
        }

        private JsonArray reference(String name) {
            if (active.contains(name)) {
                throw new IllegalArgumentException("named action cycle: " + String.join(" -> ", active) + " -> " + name);
            }
            JsonArray cached = compiled.get(name);
            if (cached != null) {
                return cached;
            }
            JsonArray source = library.get(name);
            if (source == null) {
                throw new IllegalArgumentException("unknown named action: " + name);
            }
            if (active.size() >= MAX_CALL_DEPTH) {
                throw new IllegalArgumentException("named actions exceed " + MAX_CALL_DEPTH + " nested calls");
            }
            active.addLast(name);
            depths.put(name, 1);
            try {
                JsonArray resolved = walk(source, true, 0).getAsJsonArray();
                if (depths.get(name) > MAX_CALL_DEPTH) {
                    throw new IllegalArgumentException("named actions exceed " + MAX_CALL_DEPTH + " nested calls");
                }
                compiled.put(name, resolved);
                return resolved;
            } finally {
                active.removeLast();
            }
        }

        private JsonElement walk(JsonElement source, boolean actions, int depth) {
            if (++nodes > MAX_EXPANDED_NODES || depth > 256) {
                throw new IllegalArgumentException("named action expansion exceeds the document complexity limit");
            }
            if (source.isJsonArray()) {
                JsonArray result = new JsonArray();
                for (JsonElement entry : source.getAsJsonArray()) {
                    result.add(walk(entry, actions, depth + 1));
                }
                return result;
            }
            if (!source.isJsonObject()) {
                return source.deepCopy();
            }
            JsonObject object = source.getAsJsonObject();
            if (actions && object.has("type") && object.get("type").isJsonPrimitive()
                && object.get("type").getAsString().equals("call")) {
                return call(object, depth);
            }
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                result.add(entry.getKey(), walk(entry.getValue(), actions || ACTION_LISTS.contains(entry.getKey()), depth + 1));
            }
            return result;
        }

        private JsonObject call(JsonObject source, int depth) {
            JsonElement name = source.get("action");
            if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("call requires a named action identifier");
            }
            JsonArray branch = reference(name.getAsString());
            if (!active.isEmpty()) {
                depths.merge(active.peekLast(), 1 + depths.get(name.getAsString()), Math::max);
            }
            JsonObject result = new JsonObject();
            result.addProperty("type", "if");
            result.add("when", source.has("when") ? source.get("when").deepCopy() : new JsonPrimitive("true"));
            if (source.has("trigger")) {
                result.add("trigger", source.get("trigger").deepCopy());
            }
            if (source.has("cooldownTicks")) {
                result.add("cooldownTicks", source.get("cooldownTicks").deepCopy());
            }
            result.add("then", walk(branch, true, depth + 1));
            return result;
        }
    }
}
