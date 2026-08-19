package art.arcane.gloss.config.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.ButtonComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public record ButtonComponentData(
    @SerializedName("highlightModifier")
    float highlightMod,
    List<MenuActionData> actions,
    @SerializedName("icon")
    MenuIconData iconData,
    HitboxData hitbox
) implements ComponentData {

  public MenuComponentType getType() {
    return MenuComponentType.BUTTON;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new ButtonComponent(session, data);
  }
}
