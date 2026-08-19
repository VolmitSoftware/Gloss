package art.arcane.gloss.config.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.components.ToggleComponent;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public record ToggleComponentData(
    @SerializedName("highlightModifier")
    float highlightMod,
    String condition,
    String expectedValue,
    List<MenuActionData> trueActions,
    List<MenuActionData> falseActions,
    MenuIconData trueIcon,
    MenuIconData falseIcon
) implements ComponentData {

  public MenuComponentType getType() {
    return MenuComponentType.TOGGLE;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new ToggleComponent(session, data);
  }
}
