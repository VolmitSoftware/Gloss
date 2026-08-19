package art.arcane.gloss.panel;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.menu.components.ClickableComponent;

import java.util.Objects;

public record PanelClickTarget(PanelViewSession view, ClickableComponent<?> component, double distance) {
  public PanelClickTarget {
    view = Objects.requireNonNull(view, "view");
    component = Objects.requireNonNull(component, "component");
    if (!Double.isFinite(distance) || distance < 0.0D) {
      throw new IllegalArgumentException("distance must be finite and non-negative");
    }
  }

  public void dispatch(HoloClickTrigger trigger) {
    view.dispatchClick(component, trigger);
  }
}
