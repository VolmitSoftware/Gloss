package art.arcane.gloss.enums;

import art.arcane.gloss.config.icon.AnimatedImageData;
import art.arcane.gloss.config.icon.BlockIconData;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.EntityIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.config.icon.TextImageIconData;
import art.arcane.volmlib.util.json.EnumType;

public enum MenuIconType implements EnumType.Values<MenuIconData> {
  ITEM("item", ItemIconData.class),
  BLOCK("block", BlockIconData.class),
  CUSTOM_ITEM("customItem", CustomItemIconData.class),
  ANIMATED_TEXT_IMAGE("animatedTextImage", AnimatedImageData.class),
  TEXT_IMAGE("textImage", TextImageIconData.class),
  TEXT("text", TextIconData.class),
  ENTITY("entity", EntityIconData.class),
  ITEM_STACK("itemStack", null);

  private final String value;
  private final Class<? extends MenuIconData> type;

  MenuIconType(String value, Class<? extends MenuIconData> type) {
    this.value = value;
    this.type = type;
  }

  public String getSerializedName() {
    return value;
  }

  @Override
  public Class<? extends MenuIconData> getType() {
    return type;
  }
}
