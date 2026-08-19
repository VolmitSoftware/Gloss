package art.arcane.gloss.api;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.bukkit.event.block.Action;

import java.io.IOException;

@JsonAdapter(HoloClickTrigger.Adapter.class)
public enum HoloClickTrigger {
  ANY("any"),
  LEFT_CLICK("left_click"),
  RIGHT_CLICK("right_click"),
  SHIFT_LEFT_CLICK("shift_left_click"),
  SHIFT_RIGHT_CLICK("shift_right_click");

  private final String serializedValue;

  HoloClickTrigger(String serializedValue) {
    this.serializedValue = serializedValue;
  }

  public boolean matches(HoloClickTrigger interaction) {
    return this == ANY || this == interaction;
  }

  public static HoloClickTrigger fromInteraction(Action action, boolean sneaking) {
    return switch (action) {
      case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> sneaking ? SHIFT_LEFT_CLICK : LEFT_CLICK;
      case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> sneaking ? SHIFT_RIGHT_CLICK : RIGHT_CLICK;
      default -> throw new IllegalArgumentException("Action is not a left or right click: " + action);
    };
  }

  static final class Adapter extends TypeAdapter<HoloClickTrigger> {
    @Override
    public void write(JsonWriter out, HoloClickTrigger value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      out.value(value.serializedValue);
    }

    @Override
    public HoloClickTrigger read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      if (in.peek() != JsonToken.STRING) {
        throw new JsonParseException("Action trigger must be a string");
      }
      String serializedValue = in.nextString();
      for (HoloClickTrigger trigger : values()) {
        if (trigger.serializedValue.equals(serializedValue)) {
          return trigger;
        }
      }
      throw new JsonParseException("Unknown action trigger: " + serializedValue);
    }
  }
}
