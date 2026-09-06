package art.arcane.gloss.config.icon;

import art.arcane.gloss.api.IconArgbColor;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public final class IconArgbColorAdapter extends TypeAdapter<IconArgbColor> {
    @Override
    public void write(JsonWriter out, IconArgbColor value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(value.hex());
    }

    @Override
    public IconArgbColor read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (in.peek() != JsonToken.STRING) {
            throw new JsonParseException("ARGB colors must be strings using #AARRGGBB");
        }
        try {
            return IconArgbColor.parse(in.nextString());
        } catch (IllegalArgumentException failure) {
            throw new JsonParseException(failure.getMessage(), failure);
        }
    }
}
