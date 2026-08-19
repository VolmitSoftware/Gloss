package art.arcane.gloss.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The positional-to-keyed convenience pre-pass is scoped to the six subtrees ported from HoloUi
 * (menu, panel, preview, item, sync, import). Native Gloss subtrees (hologram, board, emoji, ...)
 * stay strictly keyed: the pre-pass must never touch them.
 */
class GlossCommandNormalizeArgsTest {

  @Test
  void onlyThePortedSubtreesAreScoped() {
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"menu", "open", "shop"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"menus"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"panel", "near", "24"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"panels"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"preview"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"previews", "reset", "chest"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"item", "export"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"sync", "list"}));
    assertTrue(GlossCommandService.isScopedPositionalRoot(new String[]{"import", "preview", "source=holoui"}));

    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"hologram", "create", "spawn"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"board", "create", "spawn"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"emoji", "reset", "wave"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"animations", "reset"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"bubbles", "style", "clear"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"tablist", "reset"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"motd", "reset"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"status"}));
    assertFalse(GlossCommandService.isScopedPositionalRoot(new String[]{"reload"}));
  }

  @Test
  void bareMenuOpenRewritesToKeyedMenu() {
    assertArrayEquals(
        new String[]{"menu", "open", "menu=shop"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu", "open", "shop"}));
    assertArrayEquals(
        new String[]{"menu", "open", "menu=*"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu", "open", "*"}));
    assertArrayEquals(
        new String[]{"menu", "open", "menu=shop"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu", "open", "menu=shop"}));
    assertArrayEquals(
        new String[]{"menu", "open", "help"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu", "open", "help"}));
  }

  @Test
  void bareGroupsUseTheirNaturalListAction() {
    assertArrayEquals(new String[]{"menu", "list"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu"}));
    assertArrayEquals(new String[]{"menu", "list"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menus"}));
    assertArrayEquals(new String[]{"panel", "list"},
        GlossCommandService.normalizePositionalArgs(new String[]{"panels"}));
    assertArrayEquals(new String[]{"preview", "list"},
        GlossCommandService.normalizePositionalArgs(new String[]{"previews"}));
    assertArrayEquals(new String[]{"sync"},
        GlossCommandService.normalizePositionalArgs(new String[]{"sync"}));
  }

  @Test
  void barePreviewsResetRewritesToKeyedName() {
    assertArrayEquals(
        new String[]{"previews", "reset", "name=chest"},
        GlossCommandService.normalizePositionalArgs(new String[]{"previews", "reset", "chest"}));
    assertArrayEquals(
        new String[]{"preview", "dump", "chest"},
        GlossCommandService.normalizePositionalArgs(new String[]{"preview", "dump", "chest"}));
  }

  @Test
  void panelCreateAcceptsAnOptionalPositionalMenu() {
    assertArrayEquals(
        new String[]{"panel", "create", "welcome"},
        GlossCommandService.normalizePositionalArgs(new String[]{"panel", "create", "welcome"}));
    assertArrayEquals(
        new String[]{"panel", "create", "welcome", "menu=menus/welcome"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"panel", "create", "welcome", "menus/welcome"}));
    assertArrayEquals(
        new String[]{"panel", "create", "welcome", "menu=menus/w"},
        GlossCommandService.normalizeTabArgs(
            new String[]{"panel", "create", "welcome", "menus/w"}));
  }

  @Test
  void barePanelNearAndListValuesRewriteToKeyedValues() {
    assertArrayEquals(
        new String[]{"panels", "near", "radius=24"},
        GlossCommandService.normalizePositionalArgs(new String[]{"panels", "near", "24"}));
    assertArrayEquals(
        new String[]{"panels", "near", "radius=2"},
        GlossCommandService.normalizeTabArgs(new String[]{"panels", "near", "2"}));
    assertArrayEquals(
        new String[]{"panels", "list", "page=2"},
        GlossCommandService.normalizePositionalArgs(new String[]{"panels", "list", "2"}));
    assertArrayEquals(
        new String[]{"panels", "list", "page="},
        GlossCommandService.normalizeTabArgs(new String[]{"panels", "list", ""}));
  }

  @Test
  void multiWordRowTextBecomesOneKeyedDirectorArgument() {
    assertArrayEquals(
        new String[]{"menus", "addrow", "shop", "text=<gold>Hello brave world"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"menus", "addrow", "shop", "<gold>Hello", "brave", "world"}));
    assertArrayEquals(
        new String[]{"panels", "setrow", "spawn/info", "2", "text=Click = to continue"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"panels", "setrow", "spawn/info", "2", "text=Click", "=", "to", "continue"}));
  }

  @Test
  void menuCreateTreatsAllTrailingWordsAsOneTextValue() {
    assertArrayEquals(
        new String[]{"menu", "create", "spawn/welcome", "text=<gold>Welcome brave world"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"menu", "create", "spawn/welcome", "<gold>Welcome", "brave", "world"}));
    assertArrayEquals(
        new String[]{"menu", "create", "spawn/welcome", "text=Say \"hello\" <red>now"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"menu", "create", "spawn/welcome", "text=Say", "\"hello\"", "<red>now"}));
    assertArrayEquals(
        new String[]{"menu", "create", "spawn/welcome", "text="},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu", "create", "spawn/welcome"}));
    assertArrayEquals(
        new String[]{"menu", "create", "spawn/welcome", "text=*"},
        GlossCommandService.normalizePositionalArgs(new String[]{"menu", "create", "spawn/welcome", "*"}));
    assertTrue(GlossCommandService.defersAutomaticOutcomeSound(
        new String[]{"menu", "create", "spawn/welcome", "text="}));
    assertFalse(GlossCommandService.defersAutomaticOutcomeSound(
        new String[]{"panel", "create", "spawn/welcome"}));
    assertFalse(GlossCommandService.defersAutomaticOutcomeSound(
        new String[]{"menu", "new", "spawn/welcome"}));
  }

  @Test
  void contentValuesAndImagePathsBecomeOneKeyedDirectorArgument() {
    assertArrayEquals(
        new String[]{"menus", "seticon", "shops/main", "2", "text", "value=<gold>Buy now"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"menus", "seticon", "shops/main", "2", "text", "<gold>Buy", "now"}));
    assertArrayEquals(
        new String[]{"panels", "style", "spawn/info", "1", "backgroundArgb", "value=#CC112233"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"panels", "style", "spawn/info", "1", "backgroundArgb", "#CC112233"}));
    assertArrayEquals(
        new String[]{"panels", "image", "spawn/info", "path=event boards/welcome.png"},
        GlossCommandService.normalizePositionalArgs(
            new String[]{"panels", "image", "spawn/info", "event", "boards/welcome.png"}));
  }

  @Test
  void bareTabPrefixesRewriteToKeyedValues() {
    assertArrayEquals(
        new String[]{"menu", "open", "menu=sh"},
        GlossCommandService.normalizeTabArgs(new String[]{"menu", "open", "sh"}));
    assertArrayEquals(
        new String[]{"menu", "open", "menu="},
        GlossCommandService.normalizeTabArgs(new String[]{"menu", "open", ""}));
    assertArrayEquals(
        new String[]{"previews", "reset", "name=ch"},
        GlossCommandService.normalizeTabArgs(new String[]{"previews", "reset", "ch"}));
  }

  @Test
  void positionalTabSuggestionsAreReturnedAsBareValues() {
    assertEquals(
        List.of("shop", "showcase"),
        GlossCommandService.restorePositionalSuggestions(
            new String[]{"menu", "open", "sh"}, List.of("menu=shop", "menu=showcase")));
    assertEquals(
        List.of("chest"),
        GlossCommandService.restorePositionalSuggestions(
            new String[]{"previews", "reset", "ch"}, List.of("name=chest")));
    assertEquals(
        List.of("24"),
        GlossCommandService.restorePositionalSuggestions(
            new String[]{"panels", "near", "2"}, List.of("radius=24")));
    assertEquals(
        List.of("2"),
        GlossCommandService.restorePositionalSuggestions(
            new String[]{"panels", "list", "2"}, List.of("page=2")));
    assertEquals(
        List.of("menus/welcome"),
        GlossCommandService.restorePositionalSuggestions(
            new String[]{"panel", "create", "welcome", "menus/w"},
            List.of("menu=menus/welcome")));
  }

  @Test
  void keyedTabSuggestionsRemainKeyed() {
    assertEquals(
        List.of("menu=shop"),
        GlossCommandService.restorePositionalSuggestions(
            new String[]{"menu", "open", "menu=sh"}, List.of("menu=shop")));
  }
}
