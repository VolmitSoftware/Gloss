package art.arcane.gloss.config.icon;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;

@JsonAdapter(IconArgbColor.Adapter.class)
public record IconArgbColor(int argb) {
  public static final IconArgbColor TRANSPARENT = new IconArgbColor(0);

  public static IconArgbColor parse(String value) {
    if (value == null || !value.matches("#[0-9A-Fa-f]{8}")) {
      throw new IllegalArgumentException("ARGB colors must use #AARRGGBB");
    }
    long bits = Long.parseUnsignedLong(value.substring(1), 16);
    return new IconArgbColor((int) bits);
  }

  public String hex() {
    return String.format(Locale.ROOT, "#%08X", Integer.toUnsignedLong(argb));
  }

  public static final class Adapter extends TypeAdapter<IconArgbColor> {
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
      String value = in.nextString();
      try {
        return IconArgbColor.parse(value);
      } catch (IllegalArgumentException exception) {
        throw new JsonParseException(exception.getMessage(), exception);
      }
    }
  }
}
