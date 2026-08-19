package art.arcane.gloss.config.icon;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

@JsonAdapter(IconBillboard.Adapter.class)
public enum IconBillboard {
  @SerializedName("fixed")
  FIXED("fixed", (byte) 0),
  @SerializedName("vertical")
  VERTICAL("vertical", (byte) 1),
  @SerializedName("horizontal")
  HORIZONTAL("horizontal", (byte) 2),
  @SerializedName("center")
  CENTER("center", (byte) 3);

  private final String serializedValue;
  private final byte metadataValue;

  IconBillboard(String serializedValue, byte metadataValue) {
    this.serializedValue = serializedValue;
    this.metadataValue = metadataValue;
  }

  public byte metadataValue() {
    return metadataValue;
  }

  public static final class Adapter extends TypeAdapter<IconBillboard> {
    @Override
    public void write(JsonWriter out, IconBillboard value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      out.value(value.serializedValue);
    }

    @Override
    public IconBillboard read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      if (in.peek() != JsonToken.STRING) {
        throw new JsonParseException("Display billboard must be fixed, vertical, horizontal, or center");
      }
      String serializedValue = in.nextString();
      for (IconBillboard billboard : values()) {
        if (billboard.serializedValue.equals(serializedValue)) {
          return billboard;
        }
      }
      throw new JsonParseException("Unknown display billboard: " + serializedValue);
    }
  }
}
