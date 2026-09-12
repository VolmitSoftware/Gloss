package art.arcane.gloss.config.components;

import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.components.SliderComponent;
import com.google.gson.annotations.SerializedName;

/** A clickable value between two bounds; a click steps it, a shift-click steps it five times. */
public record SliderComponentData(
    @SerializedName("var")
    String variable,
    Float min,
    Float max,
    Float step,
    Float width,
    String label,
    IconDisplayStyle style
) implements ComponentData {

  public static final float SHIFT_MULTIPLIER = 5F;

  public SliderComponentData {
    variable = variable == null ? "" : variable.trim();
    if (!variable.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("slider var must match [a-z][a-z0-9_]*: " + variable);
    }
    min = min == null ? 0F : min;
    max = max == null ? 100F : max;
    if (!(min < max)) {
      throw new IllegalArgumentException("slider " + variable + " requires min < max");
    }
    step = step == null || step <= 0F ? 1F : step;
    width = width == null || width <= 0F ? 2.0F : width;
    label = label == null ? "" : label;
  }

  @Override
  public MenuComponentType getType() {
    return MenuComponentType.SLIDER;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new SliderComponent(session, data);
  }
}
