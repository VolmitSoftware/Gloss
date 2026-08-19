package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.theme.DirectorProduct;
import art.arcane.volmlib.util.director.theme.DirectorThemes;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class MenuCommandClickTargetTest {

  private static final DirectorMiniMenu.Theme THEME =
      DirectorMiniMenu.Theme.fromDirectorTheme(DirectorThemes.forProduct(DirectorProduct.GLOSS));

  @Test
  public void aMenuEntryClickRunsTheSimpleOpenForm() {
    String line = CommandGlossMenu.menuEntryLine("shop", "Click to open shop.", THEME);

    assertTrue(line, line.contains("<click:run_command:/gloss menu open shop>"));
  }
}
