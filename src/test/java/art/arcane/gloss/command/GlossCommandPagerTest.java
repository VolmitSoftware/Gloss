package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GlossCommandPagerTest {
  @Test
  public void pageCountKeepsOneEmptyPageAndRoundsUp() {
    assertEquals(1, GlossCommandPager.pageCount(0, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(1, GlossCommandPager.pageCount(15, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(2, GlossCommandPager.pageCount(16, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(3, GlossCommandPager.pageCount(45, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(143165577, GlossCommandPager.pageCount(Integer.MAX_VALUE, GlossCommandPager.TEXT_PAGE_SIZE));
  }

  @Test
  public void pageSizesShareTheNineteenLineMenuBudget() {
    assertEquals(19, DirectorMiniMenu.MENU_LINE_COUNT);
    assertEquals(15, GlossCommandPager.TEXT_PAGE_SIZE);
    assertEquals(13, GlossCommandPager.ITEM_STATUS_PAGE_SIZE);
    assertEquals(3, GlossCommandPager.EMOJI_COLUMNS);
    assertEquals(45, GlossCommandPager.EMOJI_PAGE_SIZE);
    assertEquals(DirectorMiniMenu.MENU_LINE_COUNT, 1 + GlossCommandPager.TEXT_PAGE_SIZE + 2 + 1);
    assertEquals(DirectorMiniMenu.MENU_LINE_COUNT,
        1 + 1 + GlossCommandPager.ITEM_STATUS_PAGE_SIZE + 2 + 1 + 1);
    assertEquals(DirectorMiniMenu.MENU_LINE_COUNT,
        1 + (GlossCommandPager.EMOJI_PAGE_SIZE / GlossCommandPager.EMOJI_COLUMNS) + 2 + 1);
    assertEquals(2, GlossCommandPager.pageCount(67, GlossCommandPager.EMOJI_PAGE_SIZE));
    assertEquals(new GlossCommandPager.Window(2, 2, 45, 67, 67),
        GlossCommandPager.window(67, 2, GlossCommandPager.EMOJI_PAGE_SIZE));
  }

  @Test
  public void windowBoundsEveryPageToTheListSize() {
    assertEquals(new GlossCommandPager.Window(2, 2, 15, 25, 25),
        GlossCommandPager.window(25, 2, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new GlossCommandPager.Window(2, 2, 15, 25, 25),
        GlossCommandPager.window(25, 3, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new GlossCommandPager.Window(1, 1, 0, 0, 0),
        GlossCommandPager.window(0, 1, GlossCommandPager.TEXT_PAGE_SIZE));
  }

  @Test
  public void windowClampsPagesOutsideTheAvailableRange() {
    assertEquals(new GlossCommandPager.Window(1, 2, 0, 15, 25),
        GlossCommandPager.window(25, 0, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new GlossCommandPager.Window(1, 2, 0, 15, 25),
        GlossCommandPager.window(25, Integer.MIN_VALUE, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new GlossCommandPager.Window(2, 2, 15, 25, 25),
        GlossCommandPager.window(25, 4, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new GlossCommandPager.Window(2, 2, 15, 25, 25),
        GlossCommandPager.window(25, Integer.MAX_VALUE, GlossCommandPager.TEXT_PAGE_SIZE));
  }

  @Test
  public void hasNextTracksTheLastPage() {
    assertTrue(GlossCommandPager.window(25, 1, GlossCommandPager.TEXT_PAGE_SIZE).hasNext());
    assertFalse(GlossCommandPager.window(25, 2, GlossCommandPager.TEXT_PAGE_SIZE).hasNext());
    assertFalse(GlossCommandPager.window(25, 3, GlossCommandPager.TEXT_PAGE_SIZE).hasNext());
    assertFalse(GlossCommandPager.window(0, 1, GlossCommandPager.TEXT_PAGE_SIZE).hasNext());
  }

  @Test
  public void nextCommandAppendsTheKeyedPageArgument() {
    assertEquals("/gloss emoji list page=2",
        GlossCommandPager.nextCommand("/gloss emoji list",
            GlossCommandPager.window(67, 1, GlossCommandPager.EMOJI_PAGE_SIZE)));
    assertEquals("/gloss panel list page=3",
        GlossCommandPager.nextCommand("/gloss panel list",
            GlossCommandPager.window(31, 2, GlossCommandPager.TEXT_PAGE_SIZE)));
  }

  @Test
  public void rejectsNegativeCountsAndNonPositivePageSizes() {
    assertThrows(IllegalArgumentException.class,
        () -> GlossCommandPager.pageCount(-1, GlossCommandPager.TEXT_PAGE_SIZE));
    assertThrows(IllegalArgumentException.class,
        () -> GlossCommandPager.pageCount(10, 0));
    assertThrows(IllegalArgumentException.class,
        () -> GlossCommandPager.window(-1, 1, GlossCommandPager.TEXT_PAGE_SIZE));
  }
}
