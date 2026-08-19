package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;

// component names are the JSON keys, the Gson instance applies no naming policy
public record CustomItemIconData(
    String provider,
    String item,
    int count,
    IconDisplayStyle style
) implements MenuIconData {
  public MenuIconType getType() {
    return MenuIconType.CUSTOM_ITEM;
  }
}
