package art.arcane.gloss.enums;

import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.DecoComponentData;
import art.arcane.gloss.config.components.FieldComponentData;
import art.arcane.gloss.config.components.ListComponentData;
import art.arcane.gloss.config.components.SliderComponentData;
import art.arcane.gloss.config.components.TabsComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.volmlib.util.json.EnumType;

public enum MenuComponentType implements EnumType.Values<ComponentData> {
  BUTTON("button", ButtonComponentData.class),
  DECO("decoration", DecoComponentData.class),
  TOGGLE("toggle", ToggleComponentData.class),
  LIST("list", ListComponentData.class),
  SLIDER("slider", SliderComponentData.class),
  FIELD("field", FieldComponentData.class),
  TABS("tabs", TabsComponentData.class);

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
