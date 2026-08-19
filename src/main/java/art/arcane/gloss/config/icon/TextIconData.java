package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;

public record TextIconData(String text, IconDisplayStyle style, Integer refreshTicks) implements MenuIconData {
  public static final int DEFAULT_REFRESH_TICKS = 10;
  public static final int MAX_REFRESH_TICKS = 1200;

  public TextIconData {
    if (refreshTicks != null && (refreshTicks < 0 || refreshTicks > MAX_REFRESH_TICKS)) {
      throw new IllegalArgumentException("refreshTicks must be between 0 and " + MAX_REFRESH_TICKS);
    }
  }

  public int resolvedRefreshTicks() {
    return refreshTicks == null ? DEFAULT_REFRESH_TICKS : refreshTicks;
  }

  public MenuIconType getType() {
    return MenuIconType.TEXT;
  }
}
