package art.arcane.gloss.config.icon;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

@JsonAdapter(IconTextAlignment.Adapter.class)
public enum IconTextAlignment {
  @SerializedName("center")
  CENTER("center", (byte) 0),
  @SerializedName("left")
  LEFT("left", (byte) 0x08),
  @SerializedName("right")
  RIGHT("right", (byte) 0x10);

  private final String serializedValue;
  private final byte textFlag;

  IconTextAlignment(String serializedValue, byte textFlag) {
    this.serializedValue = serializedValue;
    this.textFlag = textFlag;
  }

  public byte textFlag() {
    return textFlag;
  }

  public static final class Adapter extends TypeAdapter<IconTextAlignment> {
    @Override
    public void write(JsonWriter out, IconTextAlignment value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      out.value(value.serializedValue);
    }

    @Override
    public IconTextAlignment read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      if (in.peek() != JsonToken.STRING) {
        throw new JsonParseException("Text alignment must be center, left, or right");
      }
      String serializedValue = in.nextString();
      for (IconTextAlignment alignment : values()) {
        if (alignment.serializedValue.equals(serializedValue)) {
          return alignment;
        }
      }
      throw new JsonParseException("Unknown text alignment: " + serializedValue);
    }
  }
}
