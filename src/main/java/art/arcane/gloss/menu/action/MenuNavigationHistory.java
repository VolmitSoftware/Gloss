package art.arcane.gloss.menu.action;

import art.arcane.gloss.enums.NavigationMode;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The menu back-stack shared by in-hand sessions and world panels: the most recently left menu is
 * first, a push records the menu being left, back pops it, and home drops the whole stack. Repeated
 * pushes of the same menu are recorded, not collapsed.
 *
 * <p>The root menu is not stored here — in-hand sessions adopt one as they open, panels take theirs
 * from the panel definition — so every call that can resolve to it takes it as a parameter. Not
 * thread-safe; callers own the locking.
 */
public final class MenuNavigationHistory {
  private final Deque<String> entries = new ArrayDeque<>();

  public static String resolveTarget(NavigationMode mode, String pushTarget, String lastMenuId,
                                     String rootMenuId) {
    return switch (mode) {
      case PUSH, REPLACE -> pushTarget;
      case BACK -> lastMenuId;
      case HOME -> rootMenuId;
      case CLOSE -> null;
    };
  }

  public String last() {
    return entries.peekFirst();
  }

  public String resolveTarget(NavigationMode mode, String pushTarget, String rootMenuId) {
    return resolveTarget(mode, pushTarget, entries.peekFirst(), rootMenuId);
  }

  /**
   * Applies a navigation that has already been carried out. {@code previousMenuId} is the menu
   * being left, or null when nothing was open — which starts a fresh stack.
   */
  public void commit(NavigationMode mode, String previousMenuId) {
    switch (mode) {
      case PUSH -> {
        if (previousMenuId == null) {
          entries.clear();
        } else {
          entries.addFirst(previousMenuId);
        }
      }
      case BACK -> entries.pollFirst();
      case HOME -> entries.clear();
      case REPLACE, CLOSE -> {
      }
    }
  }

  public void record(String menuId) {
    entries.addFirst(menuId);
  }

  public void clear() {
    entries.clear();
  }
}
