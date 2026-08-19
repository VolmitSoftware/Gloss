package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.volmlib.util.json.EnumType;
import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(MenuIconData.Adapter.class)
public interface MenuIconData extends EnumType.Object<MenuIconData> {
  MenuIconType getType();

  default IconDisplayStyle style() {
    return null;
  }

  class Adapter extends EnumType<MenuIconData, MenuIconType> {
    public Adapter() {
      super(MenuIconData.class, MenuIconType.class);
    }
  }
}
