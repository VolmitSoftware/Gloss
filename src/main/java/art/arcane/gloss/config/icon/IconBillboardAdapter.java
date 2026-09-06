package art.arcane.gloss.config.icon;

import art.arcane.gloss.api.IconBillboard;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;

public final class IconBillboardAdapter extends TypeAdapter<IconBillboard> {
    @Override
    public void write(JsonWriter out, IconBillboard value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(value.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public IconBillboard read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (in.peek() != JsonToken.STRING) {
            throw new JsonParseException("Display billboard must be fixed, vertical, horizontal, center");
        }
        String value = in.nextString();
        for (IconBillboard option : IconBillboard.values()) {
            if (option.name().toLowerCase(Locale.ROOT).equals(value)) {
                return option;
            }
        }
        throw new JsonParseException("Unknown Display billboard: " + value);
    }
}
