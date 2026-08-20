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
    HitboxData hitbox,
    Integer hoverDurationTicks,
    HoverEasing hoverEasing
) implements ComponentData {

  public static final int DEFAULT_HOVER_DURATION_TICKS = 4;
  public static final int MAX_HOVER_DURATION_TICKS = 40;

  public ButtonComponentData {
    validateHover(highlightMod, hoverDurationTicks);
  }

  public int resolvedHoverDurationTicks() {
    return hoverDurationTicks == null ? DEFAULT_HOVER_DURATION_TICKS : hoverDurationTicks;
  }

  public HoverEasing resolvedHoverEasing() {
    return HoverEasing.resolve(hoverEasing);
  }

  static void validateHover(float highlightMod, Integer durationTicks) {
    if (!Float.isFinite(highlightMod)) {
      throw new IllegalArgumentException("highlightModifier must be finite");
    }
    if (durationTicks != null && (durationTicks < 0 || durationTicks > MAX_HOVER_DURATION_TICKS)) {
      throw new IllegalArgumentException("hoverDurationTicks must be between 0 and " + MAX_HOVER_DURATION_TICKS);
    }
  }

  public MenuComponentType getType() {
    return MenuComponentType.BUTTON;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new ButtonComponent(session, data);
  }
}
