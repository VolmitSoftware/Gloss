package art.arcane.gloss.surface;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;

@JsonAdapter(SurfaceKind.Adapter.class)
public enum SurfaceKind {
    ACTIONBAR,
    BOSSBAR,
    TITLE;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SurfaceKind of(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "actionbar" -> ACTIONBAR;
            case "bossbar" -> BOSSBAR;
            case "title" -> TITLE;
            default -> throw new IllegalArgumentException(
                "surface must be one of actionbar, bossbar, title: " + name);
        };
    }

    public static final class Adapter extends TypeAdapter<SurfaceKind> {
        @Override
        public SurfaceKind read(JsonReader reader) throws IOException {
            return of(reader.nextString());
        }

        @Override
        public void write(JsonWriter writer, SurfaceKind kind) throws IOException {
            writer.value(kind == null ? null : kind.wireName());
        }
    }
}
