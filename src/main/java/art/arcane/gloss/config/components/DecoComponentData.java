package art.arcane.gloss.config.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.DecoComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import com.google.gson.annotations.SerializedName;

public record DecoComponentData(
    @SerializedName("icon")
    MenuIconData iconData
) implements ComponentData {

  public MenuComponentType getType() {
    return MenuComponentType.DECO;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new DecoComponent(session, data);
  }
}
