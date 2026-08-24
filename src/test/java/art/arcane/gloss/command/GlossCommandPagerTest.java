package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GlossCommandPagerTest {
  @Test
  public void pageSizesShareTheNineteenLineMenuBudget() {
    assertEquals(19, DirectorMiniMenu.MENU_LINE_COUNT);
    assertEquals(17, GlossCommandPager.TEXT_PAGE_SIZE);
    assertEquals(15, GlossCommandPager.ITEM_STATUS_PAGE_SIZE);
    assertEquals(3, GlossCommandPager.EMOJI_COLUMNS);
    assertEquals(51, GlossCommandPager.EMOJI_PAGE_SIZE);
    assertEquals(DirectorMiniMenu.MENU_LINE_COUNT, 1 + GlossCommandPager.TEXT_PAGE_SIZE + 1);
    assertEquals(DirectorMiniMenu.MENU_LINE_COUNT,
        1 + 1 + GlossCommandPager.ITEM_STATUS_PAGE_SIZE + 1 + 1);
    assertEquals(DirectorMiniMenu.MENU_LINE_COUNT,
        1 + (GlossCommandPager.EMOJI_PAGE_SIZE / GlossCommandPager.EMOJI_COLUMNS) + 1);
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 51, 67, 67),
        GlossCommandPager.window(67, 2, GlossCommandPager.EMOJI_PAGE_SIZE));
  }

  @Test
  public void windowBoundsEveryPageToTheListSize() {
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 17, 25, 25),
        GlossCommandPager.window(25, 2, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 17, 25, 25),
        GlossCommandPager.window(25, 3, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new DirectorMiniMenu.ContentPage(1, 1, 0, 0, 0),
        GlossCommandPager.window(0, 1, GlossCommandPager.TEXT_PAGE_SIZE));
  }

  @Test
  public void windowClampsPagesOutsideTheAvailableRange() {
    assertEquals(new DirectorMiniMenu.ContentPage(1, 2, 0, 17, 25),
        GlossCommandPager.window(25, 0, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new DirectorMiniMenu.ContentPage(1, 2, 0, 17, 25),
        GlossCommandPager.window(25, Integer.MIN_VALUE, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 17, 25, 25),
        GlossCommandPager.window(25, 4, GlossCommandPager.TEXT_PAGE_SIZE));
    assertEquals(new DirectorMiniMenu.ContentPage(2, 2, 17, 25, 25),
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
  public void contentPageCommandsUseTheKeyedPageArgument() {
    DirectorMiniMenu.ContentPage middle = GlossCommandPager.window(40, 2, GlossCommandPager.TEXT_PAGE_SIZE);
    assertEquals("/gloss hologram list page=1", middle.previousCommand("/gloss hologram list"));
    assertEquals("/gloss hologram list page=3", middle.nextCommand("/gloss hologram list"));
    assertEquals("/gloss emoji list page=2",
        GlossCommandPager.window(67, 1, GlossCommandPager.EMOJI_PAGE_SIZE)
            .nextCommand("/gloss emoji list"));
  }

  @Test
  public void glossContentPagesUseTheSharedIrisNavigationLayout() {
    DirectorMiniMenu.ContentPage page = GlossCommandPager.window(40, 2, GlossCommandPager.TEXT_PAGE_SIZE);
    DirectorMiniMenu.Theme theme = DirectorMiniMenu.Theme.irisGreen();
    List<String> lines = new ArrayList<>();

    GlossCommandPager.appendHeader(lines, "/gloss emoji list", page, theme);
    GlossCommandPager.appendFooter(lines, page, "/gloss emoji list", theme);

    assertTrue(lines.get(0).contains("/gloss emoji list {2/3}"));
    assertTrue(lines.get(1).contains("〈 Page 1"));
    assertTrue(lines.get(1).contains("/gloss emoji list page=1"));
    assertTrue(lines.get(1).contains("Page 3 ❭"));
    assertTrue(lines.get(1).contains("/gloss emoji list page=3"));
  }

  @Test
  public void rejectsNegativeCountsAndNonPositivePageSizes() {
    assertThrows(IllegalArgumentException.class,
        () -> GlossCommandPager.window(-1, 1, GlossCommandPager.TEXT_PAGE_SIZE));
    assertThrows(IllegalArgumentException.class,
        () -> GlossCommandPager.window(10, 1, 0));
  }
}
