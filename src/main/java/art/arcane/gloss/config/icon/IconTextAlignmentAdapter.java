package art.arcane.gloss.config.icon;

import art.arcane.gloss.api.IconTextAlignment;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;

public final class IconTextAlignmentAdapter extends TypeAdapter<IconTextAlignment> {
    @Override
    public void write(JsonWriter out, IconTextAlignment value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(value.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public IconTextAlignment read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (in.peek() != JsonToken.STRING) {
            throw new JsonParseException("Text alignment must be center, left, right");
        }
        String value = in.nextString();
        for (IconTextAlignment option : IconTextAlignment.values()) {
            if (option.name().toLowerCase(Locale.ROOT).equals(value)) {
                return option;
            }
        }
        throw new JsonParseException("Unknown Text alignment: " + value);
    }
}
