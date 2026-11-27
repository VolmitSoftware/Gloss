package art.arcane.gloss.config.icon;

import art.arcane.gloss.api.IconDisplayStyle;

import art.arcane.gloss.enums.MenuIconType;

import java.util.List;

// component names are the JSON keys, the Gson instance applies no naming policy
public record CustomItemIconData(
    String provider,
    String item,
    int count,
    IconDisplayStyle style,
    String name,
    List<String> lore,
    String countFormat
) implements MenuIconData {
  public CustomItemIconData {
    lore = lore == null ? List.of() : List.copyOf(lore);
  }

  public MenuIconType getType() {
    return MenuIconType.CUSTOM_ITEM;
  }
}
