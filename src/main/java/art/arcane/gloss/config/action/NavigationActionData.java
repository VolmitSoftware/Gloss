package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.enums.NavigationMode;

public record NavigationActionData(String target, NavigationMode mode,
                                   HoloClickTrigger trigger) implements MenuActionData {
  @Override
  public MenuActionType getType() {
    return MenuActionType.NAVIGATE;
  }

  public NavigationMode modeOrDefault() {
    return mode == null ? NavigationMode.PUSH : mode;
  }
}
