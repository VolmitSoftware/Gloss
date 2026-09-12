package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.BookMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.List;

/** Opens a written book. The menu stays where it is; a book is something to read, not a surface. */
public record BookActionData(String title, String author, List<String> pages, HoloClickTrigger trigger,
                             String when, Integer cooldownTicks) implements MenuActionData {
  public static final int MAX_PAGES = 100;
  public static final int MAX_PAGE_LENGTH = 1024;

  public BookActionData {
    title = title == null || title.isBlank() ? "Book" : title;
    author = author == null || author.isBlank() ? "Server" : author;
    pages = copyPages(pages);
  }

  private static List<String> copyPages(List<String> pages) {
    if (pages == null) {
      return List.of();
    }
    if (pages.size() > MAX_PAGES) {
      throw new IllegalArgumentException("a book holds at most " + MAX_PAGES + " pages, got " + pages.size());
    }
    for (String page : pages) {
      if (page != null && page.length() > MAX_PAGE_LENGTH) {
        throw new IllegalArgumentException("a book page is at most " + MAX_PAGE_LENGTH + " characters");
      }
    }
    return List.copyOf(pages);
  }

  @Override
  public MenuActionType getType() {
    return MenuActionType.BOOK;
  }

  @Override
  public ActionEnvelope envelope() {
    return ActionEnvelope.of(when, cooldownTicks);
  }

  @Override
  public MenuAction<?> createAction() {
    return new BookMenuAction(this);
  }

  @Override
  public String invalidReason() {
    return pages.isEmpty() ? "declares a book with no pages" : null;
  }
}
