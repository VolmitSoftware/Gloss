package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.gloss.exceptions.MenuIconException;

public record PlayerHeadIconData(
    String player,
    IconDisplayStyle style,
    Integer refreshTicks
) implements MenuIconData {
  /**
   * One second. A head is worth re-reading far less often than a text line, but the first render of
   * a name is always the pending head, so this is really the delay before an asynchronous lookup
   * shows up on screen.
   */
  public static final int DEFAULT_REFRESH_TICKS = 20;

  /** The same ceiling {@link TextIconData} uses; one minute of ticks. */
  public static final int MAX_REFRESH_TICKS = 1200;

  public PlayerHeadIconData {
    if (refreshTicks != null && (refreshTicks < 0 || refreshTicks > MAX_REFRESH_TICKS)) {
      throw new IllegalArgumentException("refreshTicks must be between 0 and " + MAX_REFRESH_TICKS);
    }
  }

  public MenuIconType getType() {
    return MenuIconType.PLAYER_HEAD;
  }

  /**
   * Zero means never re-read the name. A head whose lookup was still in flight at spawn then stays
   * the pending head until something else respawns the component, which is a real choice for a
   * static head on a busy menu and a trap for a placeholder.
   */
  public int resolvedRefreshTicks() {
    return refreshTicks == null ? DEFAULT_REFRESH_TICKS : refreshTicks;
  }

  /**
   * The authored name or placeholder, never blank. Emptiness is the one thing that cannot degrade
   * to a fallback head: there is nothing to look up and nothing to signpost, so it is a broken icon
   * and takes the missing-icon path {@code MenuIcon.createIcon} already has for every other kind
   * (MenuIcon.java:71-82).
   */
  public String requirePlayer() throws MenuIconException {
    if (player == null || player.isBlank()) {
      throw new MenuIconException("Player head icon has no player name");
    }
    return player.trim();
  }
}
