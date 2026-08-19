package art.arcane.gloss.menu.action;

import art.arcane.gloss.enums.NavigationMode;

import java.util.Objects;

public record NavigationRequest(NavigationMode mode, String target) {
  public NavigationRequest {
    Objects.requireNonNull(mode, "mode");
    if ((mode == NavigationMode.PUSH || mode == NavigationMode.REPLACE)
        && (target == null || target.isBlank())) {
      throw new IllegalArgumentException("Navigation target is required for " + mode.name().toLowerCase());
    }
  }
}
