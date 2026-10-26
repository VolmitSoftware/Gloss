package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlossCommandPagerTest {
  private static final DirectorMiniMenu.Theme THEME = DirectorMiniMenu.Theme.irisGreen();
  private static final String WRAPPING_DETAILS = String.join(" ", IntStream.range(0, 10).mapToObj(index -> "abcde").toList());

  @Test
  public void menuPagesFillTheTwentyChatLines() {
    List<String> entries = entries(40, "menu=main");
    List<String> lines = GlossCommandPager.menu("/gloss panel list", entries, 1, "/gloss panel list", THEME);

    assertEquals(new DirectorMiniMenu.ContentPage(1, 3, 0, 18, 40),
        GlossCommandPager.window(entries, 1, GlossCommandPager.MENU_RESERVED_LINES));
    assertEquals(DirectorMiniMenu.CHAT_LINE_COUNT, lines.size());
    assertEquals(DirectorMiniMenu.CHAT_LINE_COUNT, rows(lines));
  }

  @Test
  public void wrappedEntriesTakeTheLinesTheyWrapTo() {
    List<String> entries = entries(20, WRAPPING_DETAILS);
    assertEquals(2, DirectorMiniMenu.chatRows(entries.get(0)));

    List<String> lines = GlossCommandPager.menu("/gloss panel list", entries, 1, "/gloss panel list", THEME);

    assertEquals(new DirectorMiniMenu.ContentPage(1, 3, 0, 9, 20),
        GlossCommandPager.window(entries, 1, GlossCommandPager.MENU_RESERVED_LINES));
    assertEquals(11, lines.size());
    assertTrue(rows(lines) <= DirectorMiniMenu.CHAT_LINE_COUNT);
  }

  @Test
  public void extraLinesShrinkThePage() {
    assertEquals(new DirectorMiniMenu.ContentPage(1, 3, 0, 15, 40),
        GlossCommandPager.window(entries(40, null), 1, 5));
    assertEquals(new DirectorMiniMenu.ContentPage(1, 2, 0, 20, 40),
        GlossCommandPager.window(entries(40, null), 1, 0));
  }

  @Test
  public void windowClampsPagesOutsideTheAvailableRange() {
    List<String> entries = entries(25, null);

    assertEquals(new DirectorMiniMenu.ContentPage(1, 2, 0, 18, 25),
        GlossCommandPager.window(entries, 0, GlossCommandPager.MENU_RESERVED_LINES));
    assertEquals(new DirectorMiniMenu.ContentPage(1, 2, 0, 18, 25),
        GlossCommandPager.window(entries, Integer.MIN_VALUE, GlossCommandPager.MENU_RESERVED_LINES));
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 18, 25, 25),
        GlossCommandPager.window(entries, 4, GlossCommandPager.MENU_RESERVED_LINES));
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 18, 25, 25),
        GlossCommandPager.window(entries, Integer.MAX_VALUE, GlossCommandPager.MENU_RESERVED_LINES));
    assertEquals(new DirectorMiniMenu.ContentPage(1, 1, 0, 0, 0),
        GlossCommandPager.window(List.of(), 1, GlossCommandPager.MENU_RESERVED_LINES));
  }

  @Test
  public void hasNextTracksTheLastPage() {
    List<String> entries = entries(25, null);

    assertTrue(GlossCommandPager.window(entries, 1, GlossCommandPager.MENU_RESERVED_LINES).hasNext());
    assertFalse(GlossCommandPager.window(entries, 2, GlossCommandPager.MENU_RESERVED_LINES).hasNext());
    assertFalse(GlossCommandPager.window(List.of(), 1, GlossCommandPager.MENU_RESERVED_LINES).hasNext());
  }

  @Test
  public void contentPageCommandsUseTheKeyedPageArgument() {
    DirectorMiniMenu.ContentPage middle = GlossCommandPager.window(entries(40, null), 2,
        GlossCommandPager.MENU_RESERVED_LINES);

    assertEquals("/gloss hologram list page=1", middle.previousCommand("/gloss hologram list"));
    assertEquals("/gloss hologram list page=3", middle.nextCommand("/gloss hologram list"));
  }

  @Test
  public void glossContentPagesUseTheSharedIrisNavigationLayout() {
    List<String> lines = GlossCommandPager.menu("/gloss emoji list", entries(40, null), 2, "/gloss emoji list", THEME);
    String footer = lines.get(lines.size() - 1);

    assertTrue(lines.get(0).contains("/gloss emoji list {2/3}"));
    assertTrue(footer.contains("〈 Page 1"));
    assertTrue(footer.contains("/gloss emoji list page=1"));
    assertTrue(footer.contains("Page 3 ❭"));
    assertTrue(footer.contains("/gloss emoji list page=3"));
  }

  private static List<String> entries(int count, String details) {
    List<String> entries = new ArrayList<>(count);
    for (int index = 1; index <= count; index++) {
      entries.add(GlossCommandPager.entry("entry-" + index, details, THEME));
    }
    return entries;
  }

  private static int rows(List<String> lines) {
    int rows = 0;
    for (String line : lines) {
      rows += DirectorMiniMenu.chatRows(line);
    }
    return rows;
  }
}
