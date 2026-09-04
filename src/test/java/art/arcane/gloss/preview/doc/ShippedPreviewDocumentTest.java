package art.arcane.gloss.preview.doc;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.preview.PreviewElement;
import art.arcane.gloss.util.common.TextUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract checks on the fourteen shipped documents that the golden snapshots cannot make: every
 * file compiles, and the two title paths the captures never exercised (a player-named container,
 * a material-derived name behind a glob) still render what the retired layouts drew.
 *
 * <p>The name list is the same one {@code PreviewDocumentRegistry} extracts from the jar, so a
 * document added without being registered — or registered without being added — fails here.
 */
public class ShippedPreviewDocumentTest {

  /** Index of the title label in a framed card: frame, panel, tray, title bar, title. */
  private static final int TITLE_WITH_TRAY = 4;

  static final List<String> SHIPPED = List.of(
      "chest", "ender_chest", "furnace", "brewing_stand", "hopper", "dispenser", "shelf",
      "chiseled_bookshelf", "jukebox", "beehive", "cauldron", "minecart", "furnace_minecart", "locked");

  @Test
  public void shippedDocumentsExposeShowAtEveryLevel() throws IOException {
    for (String name : SHIPPED) {
      try (InputStream stream = ShippedPreviewDocumentTest.class.getResourceAsStream("/previews/" + name + ".json")) {
        assertNotNull(name, stream);
        JsonObject document = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertTrue(name, document.get("show").getAsBoolean());
        assertTrue(name, document.getAsJsonObject("card").get("show").getAsBoolean());
        for (JsonElement element : document.getAsJsonArray("elements")) {
          assertTrue(name, element.getAsJsonObject().has("show"));
        }
      }
    }
  }

  @Test
  public void everyShippedDocumentCompiles() throws IOException {
    assertEquals(14, SHIPPED.size());
    for (String name : SHIPPED) {
      CompiledPreviewDocument doc = parse(name);
      assertEquals(name + ".json", doc.name());
    }
  }

  @Test
  public void aNamedContainerTitleReplacesTheThemeTitle() throws IOException {
    CompiledPreviewDocument doc = parse("chest");
    PreviewFakes.ContainerFake named = GoldenFakes.chest(27).customName("Loot");

    List<PreviewElement> elements = doc.build(
        PreviewStateContext.forBlock(named.build(), null, doc.varsForBlock(Material.CHEST)));

    assertEquals("Loot", title(elements));
  }

  @Test
  public void anUnnamedContainerKeepsItsLocalizedThemeTitle() throws IOException {
    CompiledPreviewDocument doc = parse("chest");

    List<PreviewElement> elements = doc.build(
        PreviewStateContext.forBlock(GoldenFakes.chest(27).build(), null, doc.varsForBlock(Material.CHEST)));

    assertEquals("Chest", title(elements));
  }

  @Test
  public void anUnnamedChestFallsBackToItsMaterialWhenLocalizationIsEmpty() throws IOException {
    CompiledPreviewDocument doc = parse("chest");
    Map<String, Object> vars = doc.varsForBlock(Material.CHEST);
    ExprScope emptyLanguage = new ExprScope() {
      @Override
      public Object variable(String name) {
        if (name.equals("customName")) {
          return "";
        }
        if (name.equals("blockType")) {
          return Material.CHEST.name();
        }
        return name.startsWith("vars.") ? vars.get(name.substring("vars.".length())) : null;
      }

      @Override
      public Object call(String name, List<Object> args) {
        return name.equals("lang") ? "" : ExprFunctions.call(name, args);
      }
    };

    assertEquals("&f&lChest", ExprEvaluator.string(doc.card().title().expr(), emptyLanguage));
  }

  /** Copper chests and shelves match by glob, so their titles come from the material name. */
  @Test
  public void globMatchedBlocksTitleThemselvesFromTheirMaterial() throws IOException {
    CompiledPreviewDocument chest = parse("chest");
    List<PreviewElement> waxed = chest.build(PreviewStateContext.forBlock(
        GoldenFakes.storage(Material.WAXED_EXPOSED_COPPER_CHEST, org.bukkit.block.Chest.class, 27).build(),
        null,
        chest.varsForBlock(Material.WAXED_EXPOSED_COPPER_CHEST)));
    assertTrue(chest.matchesBlock(Material.WAXED_EXPOSED_COPPER_CHEST));
    assertEquals("Waxed Exposed Copper Chest", title(waxed));

    CompiledPreviewDocument shelf = parse("shelf");
    List<PreviewElement> bamboo = shelf.build(PreviewStateContext.forBlock(
        PreviewFakes.shelf(3).type(Material.BAMBOO_SHELF).build(),
        null,
        shelf.varsForBlock(Material.BAMBOO_SHELF)));
    assertTrue(shelf.matchesBlock(Material.BAMBOO_SHELF));
    assertEquals("Bamboo Shelf", title(bamboo));
  }

  /** A chest boat is an inventory-holding entity with no dedicated title key, like the old theme. */
  @Test
  public void mobileInventoriesTitleThemselvesFromTheirEntityMaterial() throws IOException {
    CompiledPreviewDocument doc = parse("minecart");
    Entity boat = PreviewFakes.entity(org.bukkit.entity.EntityType.OAK_CHEST_BOAT)
        .inventory(PreviewFakes.inventory(27).build())
        .build();

    assertTrue(doc.matchesEntity(boat));
    List<PreviewElement> elements = doc.build(PreviewStateContext.forEntity(boat, null, doc.varsForEntity(boat)));

    assertEquals("Oak Chest Boat", title(elements));
    // 9 wide, 3 rows: the grid branch, not the hopper minecart's five-slot row.
    assertEquals(5 + 27, elements.size());
  }

  /**
   * The entity custom-name branch. No golden covers it: the captures only ever built an unnamed
   * cart, so this is the only gate on {@code minecart.json} honouring a name plate. The expected
   * component is the one the retired {@code forEntity} title path produced for the same fake —
   * {@code TextUtils.parse("&f&l" + name)} — verified against that code before it was deleted.
   */
  @Test
  public void aNamedEntityTitleReplacesTheThemeTitle() throws IOException {
    CompiledPreviewDocument doc = parse("minecart");
    Entity cart = GoldenFakes.hopperMinecart(GoldenFakes.minecartInventory()).customName("Ore Line").build();

    List<PreviewElement> elements = doc.build(PreviewStateContext.forEntity(cart, null, doc.varsForEntity(cart)));

    assertEquals(bold("Ore Line"), component(elements));
    // The name plate does not move the cart off the hopper minecart's five-slot row.
    assertEquals(5 + 5, elements.size());
  }

  /**
   * A whitespace-only name plate is not a name: the retired title path tested {@code isBlank()} and
   * fell through to the themed title, and the adapter's blank-collapses-to-empty rule keeps that.
   */
  @Test
  public void aBlankEntityCustomNameKeepsTheThemeTitle() throws IOException {
    CompiledPreviewDocument doc = parse("minecart");
    Entity cart = GoldenFakes.hopperMinecart(GoldenFakes.minecartInventory()).customName("   ").build();

    List<PreviewElement> elements = doc.build(PreviewStateContext.forEntity(cart, null, doc.varsForEntity(cart)));

    assertEquals(bold("Hopper Minecart"), component(elements));
  }

  @Test
  public void furnaceMinecartHasAFuelAwareCardAndReadableFallbackTitle() throws IOException {
    CompiledPreviewDocument doc = parse("furnace_minecart");
    Entity cart = PreviewFakes.entity(EntityType.FURNACE_MINECART)
        .as(PoweredMinecart.class)
        .fuel(120)
        .build();

    List<PreviewElement> elements = doc.build(PreviewStateContext.forEntity(cart, null, doc.varsForEntity(cart)));

    assertEquals("Furnace Minecart", title(elements));
    assertEquals("Fuel 6s", TextUtils.content(((PreviewElement.Label) elements.get(elements.size() - 1)).text().get()));
  }

  @Test
  public void namedFurnaceMinecartKeepsItsCustomName() throws IOException {
    CompiledPreviewDocument doc = parse("furnace_minecart");
    Entity cart = PreviewFakes.entity(EntityType.FURNACE_MINECART)
        .as(PoweredMinecart.class)
        .customName("Rail Heater")
        .build();

    List<PreviewElement> elements = doc.build(PreviewStateContext.forEntity(cart, null, doc.varsForEntity(cart)));

    assertEquals("Rail Heater", title(elements));
    assertEquals("No fuel", TextUtils.content(((PreviewElement.Label) elements.get(elements.size() - 1)).text().get()));
  }

  /**
   * The "you may not open this" preview: four unframed cells forming a padlock, and nothing else.
   * It is target-less, so it builds over a statics-only scope.
   */
  @Test
  public void theLockedDocumentDrawsOnlyThePadlockMarker() throws IOException {
    CompiledPreviewDocument doc = parse("locked");

    List<PreviewElement> elements = doc.build(PreviewStateContext.statics(doc.vars()));

    assertEquals(4, elements.size());
    for (PreviewElement element : elements) {
      assertTrue(String.valueOf(element), element instanceof PreviewElement.Cell);
    }
  }

  /** The style the title path has always drawn: {@code &f&l}, i.e. white and bold. */
  private static Component bold(String text) {
    return Component.text(text).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD);
  }

  private static Component component(List<PreviewElement> elements) {
    PreviewElement element = elements.get(TITLE_WITH_TRAY);
    assertTrue(String.valueOf(element), element instanceof PreviewElement.Label);
    return ((PreviewElement.Label) element).text().get();
  }

  private static String title(List<PreviewElement> elements) {
    PreviewElement element = elements.get(TITLE_WITH_TRAY);
    assertTrue(String.valueOf(element), element instanceof PreviewElement.Label);
    return TextUtils.content(((PreviewElement.Label) element).text().get());
  }

  private static CompiledPreviewDocument parse(String name) throws IOException {
    String resource = "/previews/" + name + ".json";
    try (InputStream stream = ShippedPreviewDocumentTest.class.getResourceAsStream(resource)) {
      assertNotNull("missing shipped document " + resource, stream);
      return PreviewDocumentParser.parse(name + ".json", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
