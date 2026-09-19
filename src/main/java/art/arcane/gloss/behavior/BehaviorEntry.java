package art.arcane.gloss.behavior;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ConditionValidationException;
import art.arcane.gloss.config.action.MenuActionData;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.bukkit.NamespacedKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One {@code on[]} entry: a trigger, its trigger-specific options (flattened beside the trigger in
 * the document), an optional {@code when} condition and permission, and the {@code do} action list.
 * Validation runs here so the adapter can prefix a refusal with the entry's path.
 */
@JsonAdapter(BehaviorEntry.Adapter.class)
public record BehaviorEntry(BehaviorTrigger trigger, Map<String, Object> options, String when, String permission,
                            List<MenuActionData> actions) {
    private static final Set<String> FIXED = Set.of("trigger", "when", "permission", "do");
    private static final int MAX_EVERY_TICKS = 20 * 60 * 60 * 24;

    public BehaviorEntry {
        Objects.requireNonNull(trigger, "trigger is required");
        options = options == null ? Map.of() : Map.copyOf(options);
        when = blankToNull(when);
        permission = blankToNull(permission);
        if (actions == null) {
            throw new IllegalArgumentException("do is required (an action list, possibly empty)");
        }
        actions = List.copyOf(actions);
        validateOptions(trigger, options);
        if (when != null) {
            try {
                ConditionCompiler.compile(new ConditionSource("when", when));
            } catch (ConditionValidationException invalid) {
                throw new IllegalArgumentException("when is invalid: " + invalid.getMessage());
            }
        }
    }

    public String region() {
        return string("region");
    }

    public int everyTicks() {
        Object value = options.get("everyTicks");
        return value instanceof Number number ? number.intValue() : 0;
    }

    public boolean globalScope() {
        return "global".equals(string("scope"));
    }

    public String pattern() {
        return string("pattern");
    }

    public String name() {
        return string("name");
    }

    public String material() {
        return string("material");
    }

    public String menu() {
        return string("menu");
    }

    public String component() {
        return string("component");
    }

    private String string(String option) {
        Object value = options.get(option);
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateOptions(BehaviorTrigger trigger, Map<String, Object> options) {
        for (String option : options.keySet()) {
            if (!trigger.accepted().contains(option)) {
                throw new IllegalArgumentException("trigger " + trigger.key() + " does not accept the option \"" + option + "\"");
            }
        }
        for (String option : trigger.required()) {
            if (!options.containsKey(option)) {
                throw new IllegalArgumentException("trigger " + trigger.key() + " requires " + option);
            }
        }
        Object everyTicks = options.get("everyTicks");
        if (everyTicks != null && (!(everyTicks instanceof Number number) || number.intValue() < 1
            || number.intValue() > MAX_EVERY_TICKS || number.doubleValue() != Math.rint(number.doubleValue()))) {
            throw new IllegalArgumentException("everyTicks must be a whole number between 1 and " + MAX_EVERY_TICKS);
        }
        Object scope = options.get("scope");
        if (scope != null && !scope.equals("player") && !scope.equals("global")) {
            throw new IllegalArgumentException("scope must be player or global");
        }
        Object pattern = options.get("pattern");
        if (pattern != null) {
            try {
                Pattern.compile(String.valueOf(pattern));
            } catch (PatternSyntaxException invalid) {
                throw new IllegalArgumentException("pattern is not a valid regular expression: " + invalid.getDescription());
            }
        }
        for (String option : List.of("region", "name", "menu", "component", "material")) {
            Object value = options.get(option);
            if (value != null && (!(value instanceof String text) || text.isBlank())) {
                throw new IllegalArgumentException(option + " must be a non-empty string");
            }
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> options) {
        Object material = options.get("material");
        if (!(material instanceof String text)) {
            return options;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(options);
        NamespacedKey key = NamespacedKey.fromString(text.trim().toLowerCase(Locale.ROOT));
        normalized.put("material", key == null ? text.trim().toLowerCase(Locale.ROOT) : key.toString());
        return normalized;
    }

    public static final class Adapter implements TypeAdapterFactory {
        private static final TypeToken<List<MenuActionData>> ACTIONS = new TypeToken<>() {
        };

        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != BehaviorEntry.class) {
                return null;
            }
            TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
            TypeAdapter<List<MenuActionData>> actions = gson.getAdapter(ACTIONS);
            return (TypeAdapter<T>) new TypeAdapter<BehaviorEntry>() {
                @Override
                public void write(JsonWriter out, BehaviorEntry value) throws IOException {
                    if (value == null) {
                        out.nullValue();
                        return;
                    }
                    JsonObject object = new JsonObject();
                    object.addProperty("trigger", value.trigger().key());
                    for (Map.Entry<String, Object> option : value.options().entrySet()) {
                        object.add(option.getKey(), gson.toJsonTree(option.getValue()));
                    }
                    if (value.when() != null) {
                        object.addProperty("when", value.when());
                    }
                    if (value.permission() != null) {
                        object.addProperty("permission", value.permission());
                    }
                    object.add("do", actions.toJsonTree(value.actions()));
                    elements.write(out, object);
                }

                @Override
                public BehaviorEntry read(JsonReader in) throws IOException {
                    String path = pathOf(in);
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        throw new IllegalArgumentException(path + ": entry must be an object");
                    }
                    JsonElement element = elements.read(in);
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException(path + ": entry must be an object");
                    }
                    JsonObject object = element.getAsJsonObject();
                    try {
                        return build(object);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException(path + ": " + invalid.getMessage());
                    }
                }

                private BehaviorEntry build(JsonObject object) {
                    JsonElement trigger = object.get("trigger");
                    BehaviorTrigger parsed = BehaviorTrigger.parse(trigger == null || trigger.isJsonNull()
                        ? null : trigger.getAsString());
                    Map<String, Object> options = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> member : object.entrySet()) {
                        if (!FIXED.contains(member.getKey()) && !member.getValue().isJsonNull()) {
                            options.put(member.getKey(), gson.fromJson(member.getValue(), Object.class));
                        }
                    }
                    JsonElement list = object.get("do");
                    List<MenuActionData> parsedActions = list == null || list.isJsonNull() ? null
                        : actions.fromJsonTree(list);
                    return new BehaviorEntry(parsed, normalize(options), optionalString(object, "when"),
                        optionalString(object, "permission"), parsedActions);
                }
            }.nullSafe();
        }

        private static String optionalString(JsonObject object, String member) {
            JsonElement value = object.get(member);
            return value == null || value.isJsonNull() ? null : value.getAsString();
        }

        private static String pathOf(JsonReader in) {
            String path = in.getPath();
            return path.startsWith("$.") ? path.substring(2) : path;
        }
    }
}
