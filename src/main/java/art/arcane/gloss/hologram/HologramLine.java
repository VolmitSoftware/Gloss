package art.arcane.gloss.hologram;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.icon.MenuIconData;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * One authored hologram line: a string of text, or an object carrying an item, head, block or
 * entity rendered next to the text column.
 */
@JsonAdapter(HologramLine.Adapter.class)
public record HologramLine(Kind kind, String value, MenuIconData item, ShowCondition show, double scale) {
    public static final double DEFAULT_SCALE = 1.0D;
    public static final double MIN_SCALE = 0.01D;
    public static final double MAX_SCALE = 64.0D;

    public enum Kind {
        TEXT,
        ITEM,
        HEAD,
        BLOCK,
        ENTITY
    }

    public HologramLine {
        if (kind == null) {
            throw new IllegalArgumentException("hologram line needs a kind");
        }
        if (kind == Kind.ITEM) {
            if (item == null) {
                throw new IllegalArgumentException("hologram item line needs an item");
            }
            value = "";
        } else {
            if (item != null) {
                throw new IllegalArgumentException("hologram " + kind.name().toLowerCase(Locale.ROOT)
                    + " line may not carry an item");
            }
            value = value == null ? "" : value;
            if (kind != Kind.TEXT && value.isBlank()) {
                throw new IllegalArgumentException("hologram " + kind.name().toLowerCase(Locale.ROOT)
                    + " line needs a value");
            }
        }
        show = show == null ? ShowCondition.ALWAYS : show;
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException("hologram line scale must be between " + MIN_SCALE + " and " + MAX_SCALE);
        }
    }

    public static HologramLine text(String text) {
        return new HologramLine(Kind.TEXT, text == null ? "" : text, null, ShowCondition.ALWAYS, DEFAULT_SCALE);
    }

    public String text() {
        return kind == Kind.TEXT ? value : "";
    }

    public boolean isText() {
        return kind == Kind.TEXT;
    }

    public static final class Adapter implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!HologramLine.class.equals(type.getRawType())) {
                return null;
            }
            return (TypeAdapter<T>) new LineAdapter(gson.getAdapter(JsonElement.class),
                gson.getAdapter(MenuIconData.class), gson.getAdapter(ShowCondition.class));
        }
    }

    private static final class LineAdapter extends TypeAdapter<HologramLine> {
        private final TypeAdapter<JsonElement> elements;
        private final TypeAdapter<MenuIconData> icons;
        private final TypeAdapter<ShowCondition> conditions;

        private LineAdapter(TypeAdapter<JsonElement> elements, TypeAdapter<MenuIconData> icons,
                            TypeAdapter<ShowCondition> conditions) {
            this.elements = elements;
            this.icons = icons;
            this.conditions = conditions;
        }

        @Override
        public void write(JsonWriter out, HologramLine value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            if (value.kind() == Kind.TEXT && value.show().isAlwaysVisible() && value.scale() == DEFAULT_SCALE) {
                out.value(value.text());
                return;
            }
            JsonObject object = new JsonObject();
            switch (value.kind()) {
                case TEXT -> object.add("text", new JsonPrimitive(value.text()));
                case ITEM -> object.add("item", icons.toJsonTree(value.item()));
                case HEAD -> object.add("head", new JsonPrimitive(value.value()));
                case BLOCK -> object.add("block", new JsonPrimitive(value.value()));
                case ENTITY -> object.add("entity", new JsonPrimitive(value.value()));
            }
            if (!value.show().isAlwaysVisible()) {
                object.add("show", conditions.toJsonTree(value.show()));
            }
            if (value.scale() != DEFAULT_SCALE) {
                object.add("scale", new JsonPrimitive(value.scale()));
            }
            elements.write(out, object);
        }

        @Override
        public HologramLine read(JsonReader in) throws IOException {
            JsonElement element = elements.read(in);
            if (element == null || element.isJsonNull()) {
                return text("");
            }
            if (element.isJsonPrimitive()) {
                return text(element.getAsString());
            }
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("hologram line must be text or an object at " + in.getPath());
            }
            JsonObject object = element.getAsJsonObject();
            Kind kind = null;
            String value = null;
            MenuIconData item = null;
            ShowCondition show = null;
            double scale = DEFAULT_SCALE;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                switch (entry.getKey()) {
                    case "text" -> {
                        kind = requireSingleContent(kind, Kind.TEXT);
                        value = entry.getValue().getAsString();
                    }
                    case "item" -> {
                        kind = requireSingleContent(kind, Kind.ITEM);
                        item = icons.fromJsonTree(entry.getValue());
                    }
                    case "head" -> {
                        kind = requireSingleContent(kind, Kind.HEAD);
                        value = entry.getValue().getAsString();
                    }
                    case "block" -> {
                        kind = requireSingleContent(kind, Kind.BLOCK);
                        value = entry.getValue().getAsString();
                    }
                    case "entity" -> {
                        kind = requireSingleContent(kind, Kind.ENTITY);
                        value = entry.getValue().getAsString();
                    }
                    case "show" -> show = conditions.fromJsonTree(entry.getValue());
                    case "scale" -> scale = entry.getValue().getAsDouble();
                    default -> throw new IllegalArgumentException("hologram line has unknown key "
                        + entry.getKey() + " at " + in.getPath());
                }
            }
            if (kind == null) {
                throw new IllegalArgumentException("hologram line needs text, item, head, block or entity at "
                    + in.getPath());
            }
            if (kind != Kind.TEXT && show != null) {
                throw new IllegalArgumentException("only text hologram lines accept show at " + in.getPath());
            }
            return new HologramLine(kind, value, item, show, scale);
        }

        private static Kind requireSingleContent(Kind current, Kind next) {
            if (current != null) {
                throw new IllegalArgumentException("hologram line declares both "
                    + current.name().toLowerCase(Locale.ROOT) + " and "
                    + next.name().toLowerCase(Locale.ROOT));
            }
            return next;
        }
    }
}
