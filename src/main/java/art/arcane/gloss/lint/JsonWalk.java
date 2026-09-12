package art.arcane.gloss.lint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.BiConsumer;

/** Visits every node of a document with its JSON pointer, so a rule can say where it looked. */
final class JsonWalk {
    private JsonWalk() {
    }

    static void visit(JsonElement root, BiConsumer<String, JsonElement> visitor) {
        visit(root, "", visitor);
    }

    private static void visit(JsonElement element, String pointer,
                              BiConsumer<String, JsonElement> visitor) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        visitor.accept(pointer, element);
        if (element.isJsonArray()) {
            for (int index = 0; index < element.getAsJsonArray().size(); index++) {
                visit(element.getAsJsonArray().get(index), pointer + "/" + index, visitor);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            visit(entry.getValue(), pointer + "/" + escape(entry.getKey()), visitor);
        }
    }

    static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
