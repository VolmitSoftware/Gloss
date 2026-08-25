package art.arcane.gloss.config.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.ShippedResources;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import org.bukkit.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuCatalogDefaultsTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private Object previousServer;
  private Gloss previousGloss;
  private MenuCatalog catalog;

  @Before
  public void installGloss() throws Exception {
    Server server = CharacterizationSupport.server(Map.of());
    previousServer = CharacterizationSupport.installServer(server);
    Gloss gloss = CharacterizationSupport.bareGloss(server);
    CharacterizationSupport.setField(gloss, "persistenceCoordinator", new GlossPersistenceCoordinator());
    previousGloss = CharacterizationSupport.installGloss(gloss);
  }

  @After
  public void restoreStatics() throws Exception {
    if (catalog != null) {
      catalog.shutdown();
    }
    CharacterizationSupport.restoreGloss(previousGloss);
    CharacterizationSupport.restoreServer(previousServer);
  }

  @Test
  public void enabledMenusExtractAndLoadTheShippedDefault() throws Exception {
    File data = temp.newFolder("enabled");

    catalog = new MenuCatalog(data, true);

    File document = new File(data, "menus/default.json");
    assertTrue(document.isFile());
    assertEquals(ShippedResources.readText(MenuBaselines.DEFAULT_MENU_RESOURCE),
        Files.readString(document.toPath(), StandardCharsets.UTF_8));
    assertTrue(catalog.exists("default"));
  }

  @Test
  public void disabledMenusLeaveTheDefaultAbsentUntilEnabled() throws Exception {
    File data = temp.newFolder("disabled");
    catalog = new MenuCatalog(data, false);

    assertFalse(new File(data, "menus").exists());
    assertFalse(catalog.exists("default"));

    catalog.loadShippedDefaults(true);

    assertTrue(new File(data, "menus/default.json").isFile());
    assertTrue(catalog.exists("default"));
  }

  @Test
  public void existingDefaultMenuIsNeverOverwritten() throws Exception {
    File data = temp.newFolder("edited");
    File document = new File(data, "menus/default.json");
    assertTrue(document.getParentFile().mkdirs());
    String edited = MenuBaselines.simpleHologramSource("default", "&bOperator menu");
    Files.writeString(document.toPath(), edited, StandardCharsets.UTF_8);

    catalog = new MenuCatalog(data, true);

    assertEquals(edited, Files.readString(document.toPath(), StandardCharsets.UTF_8));
    assertEquals(edited, catalog.source("default").orElseThrow());
  }
}
