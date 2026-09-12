package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.SliderComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.icon.MenuIcon;

/**
 * A value the viewer nudges by clicking. Left adds a step, right subtracts one, and holding shift
 * multiplies the step so a 0..100 slider is not forty clicks wide.
 */
public final class SliderComponent extends ClickableComponent<SliderComponentData> {

  public SliderComponent(MenuSession session, MenuComponentData data) {
    super(session, data, 0F, null, 0, art.arcane.gloss.config.components.HoverEasing.resolve(null));
  }

  /** The value this click lands on, clamped to the slider's own range. */
  public static double step(SliderComponentData data, double current, HoloClickTrigger trigger) {
    double step = data.step() * (shifted(trigger) ? SliderComponentData.SHIFT_MULTIPLIER : 1D);
    double next = current + (forward(trigger) ? step : -step);
    return Math.clamp(next, data.min(), data.max());
  }

  /** Applies one click to the session; returns the value the slider now holds. */
  public static double apply(SliderComponentData data, SessionVariables variables, HoloClickTrigger trigger) {
    double current = current(data, variables);
    double next = step(data, current, trigger);
    variables.set(data.variable(), next);
    return next;
  }

  private static double current(SliderComponentData data, SessionVariables variables) {
    Object value = variables.get(data.variable());
    if (value instanceof Number number) {
      return Math.clamp(number.doubleValue(), data.min(), data.max());
    }
    return data.min();
  }

  private static boolean forward(HoloClickTrigger trigger) {
    return trigger == HoloClickTrigger.LEFT_CLICK || trigger == HoloClickTrigger.SHIFT_LEFT_CLICK
        || trigger == HoloClickTrigger.ANY;
  }

  private static boolean shifted(HoloClickTrigger trigger) {
    return trigger == HoloClickTrigger.SHIFT_LEFT_CLICK || trigger == HoloClickTrigger.SHIFT_RIGHT_CLICK;
  }

  @Override
  public MenuIcon<?> createIcon() {
    return MenuIcon.createIcon(session, location, new TextIconData(data.label(), data.style(), null, null), this);
  }

  @Override
  public void onClick(HoloClickTrigger trigger) {
    if (!isInteractable()) {
      return;
    }
    apply(data, session.variables(), trigger);
  }
}
