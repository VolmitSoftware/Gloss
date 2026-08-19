package art.arcane.gloss.enums;

import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.DecoComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.volmlib.util.json.EnumType;

public enum MenuComponentType implements EnumType.Values<ComponentData> {
  BUTTON("button", ButtonComponentData.class),
  DECO("decoration", DecoComponentData.class),
  TOGGLE("toggle", ToggleComponentData.class);

  private final String serializedName;
  private final Class<? extends ComponentData> type;

  MenuComponentType(String serializedName, Class<? extends ComponentData> type) {
    this.serializedName = serializedName;
    this.type = type;
  }

  public String getSerializedName() {
    return serializedName;
  }

  @Override
  public Class<? extends ComponentData> getType() {
    return type;
  }
}
