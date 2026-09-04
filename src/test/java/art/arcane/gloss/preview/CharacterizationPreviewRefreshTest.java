package art.arcane.gloss.preview;

import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.preview.doc.PreviewFakes;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument;
import art.arcane.gloss.preview.doc.PreviewDocumentParser;
import art.arcane.gloss.preview.doc.PreviewStateContext;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Characterization pins for the preview refresh loop (plan items T17/D2 and the Char-3 cadence
 * item): the access recheck fires every {@code ACCESS_RECHECK_INTERVAL} (10) ticks, contents
 * refresh every {@code REFRESH_INTERVAL} (4) ticks, and every refresh re-evaluates each dynamic
 * element from its live source.
 *
 * <p>The label-supplier counts double as the D2 memo boundary guard: the plan's hard rule is
 * "never memoize above {@code ExprEvaluator.string}" — the element suppliers must keep being
 * invoked once per refresh even after the parse below them is memoized. A lane that stops
 * invoking them on refresh breaks these counts.
 *
 * <p>Visuals are forced hidden (scale factor 0) so the loop runs its full refresh path headlessly
 * without ever reaching the packet layer.
 */
public class CharacterizationPreviewRefreshTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-00000000f0f0");

  @Before
  public void hideVisualsForTheViewer() throws ReflectiveOperationException {
    scaleFactors().put(VIEWER, 0.0D);
  }

  @After
  public void restoreScaleFactors() throws ReflectiveOperationException {
    scaleFactors().remove(VIEWER);
  }

  @Test
  public void hiddenDocumentRefreshesAndReturnsWithItsCard() throws ReflectiveOperationException {
    CompiledPreviewDocument document = PreviewDocumentParser.parse("show.json", """
        {"show":"burnTime > 0","card":{"title":"'Title'"},
         "elements":[{"type":"label","text":"'Body'"}]}
        """);
    PreviewFakes.FurnaceFake furnace = PreviewFakes.furnace().burnTime(0).gameTime(0);
    PreviewStateContext context = PreviewStateContext.forBlock(furnace.build(), null, Map.of());
    ContainerPreview preview = preview(document.build(context), true);
    trackVisibility(preview, document, context);
    preview.open();
    assertTrue(preview.tick());
    assertEquals(0, rendered(preview).size());

    furnace.burnTime(20).gameTime(1);
    advanceRefresh(preview);
    assertEquals(5, rendered(preview).size());
    Object unchanged = rendered(preview);
    furnace.burnTime(10).gameTime(2);
    advanceRefresh(preview);
    assertSame(unchanged, rendered(preview));

    furnace.burnTime(0).gameTime(3);
    advanceRefresh(preview);
    assertEquals(0, rendered(preview).size());
    furnace.burnTime(20).gameTime(4);
    advanceRefresh(preview);
    assertEquals(5, rendered(preview).size());
    preview.close();
  }

  @Test
  public void cardShowRebuildsChromeWithoutRemovingContent() throws ReflectiveOperationException {
    CompiledPreviewDocument document = PreviewDocumentParser.parse("card-show.json", """
        {"card":{"show":"burnTime > 0","title":"'Title'"},
         "elements":[{"type":"label","text":"'Body'"}]}
        """);
    PreviewFakes.FurnaceFake furnace = PreviewFakes.furnace().burnTime(20).gameTime(0);
    PreviewStateContext context = PreviewStateContext.forBlock(furnace.build(), null, Map.of());
    ContainerPreview preview = preview(document.build(context), true);
    trackVisibility(preview, document, context);
    preview.open();
    assertTrue(preview.tick());
    assertEquals(5, rendered(preview).size());
    furnace.burnTime(0).gameTime(1);
    advanceRefresh(preview);
    assertEquals(1, rendered(preview).size());
    furnace.burnTime(20).gameTime(2);
    advanceRefresh(preview);
    assertEquals(5, rendered(preview).size());
    preview.close();
  }

  @Test
  public void lockedPreviewChecksShowEveryFourTicks() throws ReflectiveOperationException {
    CompiledPreviewDocument document = PreviewDocumentParser.parse("locked-show.json", """
        {"show":"burnTime > 0","elements":[{"type":"label","text":"'Locked'"}]}
        """);
    PreviewFakes.FurnaceFake furnace = PreviewFakes.furnace().burnTime(0).gameTime(0);
    PreviewStateContext context = PreviewStateContext.forBlock(furnace.build(), null, Map.of());
    ContainerPreview preview = preview(document.build(context), false);
    trackVisibility(preview, document, context);
    preview.open();
    assertTrue(preview.tick());
    furnace.burnTime(20).gameTime(1);
    advanceRefresh(preview);
    assertEquals(1, rendered(preview).size());
    preview.close();
  }

  @Test
  public void elementShowRemovesAndRestoresSlots() throws ReflectiveOperationException {
    CompiledPreviewDocument document = PreviewDocumentParser.parse("slot-show.json", """
        {"elements":[{"type":"slot","size":18,"index":0,"show":"burnTime > 0"}]}
        """);
    PreviewFakes.FurnaceFake furnace = PreviewFakes.furnace().burnTime(20).gameTime(0);
    PreviewStateContext context = PreviewStateContext.forBlock(furnace.build(), null, Map.of());
    ContainerPreview preview = preview(document.build(context), true);
    trackVisibility(preview, document, context);
    preview.open();
    assertTrue(preview.tick());
    assertEquals(1, rendered(preview).size());
    furnace.burnTime(0).gameTime(1);
    advanceRefresh(preview);
    assertEquals(0, rendered(preview).size());
    furnace.burnTime(20).gameTime(2);
    advanceRefresh(preview);
    assertEquals(1, rendered(preview).size());
    preview.close();
  }

  private static void trackVisibility(ContainerPreview preview, CompiledPreviewDocument document,
                                      PreviewStateContext context) throws ReflectiveOperationException {
    Method method = ContainerPreview.class.getDeclaredMethod("trackVisibility", CompiledPreviewDocument.class,
        PreviewStateContext.class);
    method.setAccessible(true);
    method.invoke(preview, document, context);
  }

  private static List<?> rendered(ContainerPreview preview) throws ReflectiveOperationException {
    return (List<?>) CharacterizationSupport.getField(preview, "rendered");
  }

  private static void advanceRefresh(ContainerPreview preview) {
    for (int tick = 0; tick < 4; tick++) {
      assertTrue(preview.tick());
    }
  }

  @Test
  public void accessRecheckCadenceIsEveryTenTicks() throws ReflectiveOperationException {
    AtomicInteger labelReads = new AtomicInteger();
    ContainerPreview preview = preview(List.of(countingLabel(labelReads)), false);
    assertEquals("seeding evaluates each label once", 1, labelReads.get());

    preview.open();
    for (int tick = 0; tick <= 20; tick++) {
      assertTrue(preview.tick());
    }

    assertEquals("a contents-less preview refreshes only on the access cadence: "
        + "ticks 0, 10 and 20", 1 + 3, labelReads.get());
  }

  @Test
  public void contentsRefreshCadenceIsEveryFourTicks() throws ReflectiveOperationException {
    AtomicInteger labelReads = new AtomicInteger();
    ContainerPreview preview = preview(List.of(countingLabel(labelReads)), true);
    assertEquals(1, labelReads.get());

    preview.open();
    for (int tick = 0; tick <= 20; tick++) {
      assertTrue(preview.tick());
    }

    assertEquals("a contents preview refreshes on ticks 0, 4, 8, 10, 12, 16 and 20 "
        + "(the union of the 4-tick refresh and 10-tick access cadences)", 1 + 7, labelReads.get());
  }

  @Test
  public void refreshTracksTheLiveInventoryContent() throws ReflectiveOperationException {
    PreviewFakes.InventoryFake inventoryFake = PreviewFakes.inventory(27)
        .item(0, PreviewFakes.stack(Material.STICK, 3));
    Inventory inventory = inventoryFake.build();
    PreviewElement.Slot slot = new PreviewElement.Slot(0, 0, 0, 18, 0, inventory, 0);
    ContainerPreview preview = preview(List.of(slot), true);

    ItemStack seeded = pendingItem(preview);
    assertEquals(Material.STICK, seeded.getType());
    assertEquals(3, seeded.getAmount());

    preview.open();
    inventoryFake.item(0, PreviewFakes.stack(Material.ARROW, 7));
    preview.tick(); // tick 0 refreshes contents

    ItemStack refreshed = pendingItem(preview);
    assertEquals("a refresh re-reads the slot from the live inventory", Material.ARROW,
        refreshed.getType());
    assertEquals(7, refreshed.getAmount());

    inventoryFake.item(0, null);
    for (int tick = 1; tick <= 4; tick++) {
      preview.tick(); // tick 4 refreshes again
    }

    assertNull("an emptied slot refreshes to an empty pending item", pendingItem(preview));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static PreviewElement.Label countingLabel(AtomicInteger reads) {
    return new PreviewElement.Label(0, 0, 0, () -> {
      reads.incrementAndGet();
      return Component.text("count " + reads.get());
    }, () -> new ParticleText.Rendered("count " + reads.get(), List.of()), 0);
  }

  private static ItemStack pendingItem(ContainerPreview preview) throws ReflectiveOperationException {
    List<?> rendered = (List<?>) CharacterizationSupport.getField(preview, "rendered");
    return (ItemStack) CharacterizationSupport.getField(rendered.get(rendered.size() - 1), "pendingItem");
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, Double> scaleFactors() throws ReflectiveOperationException {
    Field field = PreviewScaleService.class.getDeclaredField("factors");
    field.setAccessible(true);
    return (Map<UUID, Double>) field.get(null);
  }

  private static ContainerPreview preview(List<PreviewElement> elements, boolean showsContents)
      throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(viewer(), null, null, new Vector(0.5D, 65.5D, 0.5D),
        elements, List.of(), showsContents);
  }

  private static Player viewer() {
    ItemStack mainHand = PreviewFakes.stack(Material.STICK, 1);
    PlayerInventory inventory = (PlayerInventory) CharacterizationSupport.proxy(
        new Class<?>[]{PlayerInventory.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getItemInMainHand" -> mainHand;
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getEyeLocation" -> new Location(null, 0.0D, 66.62D, 2.0D, 15.0F, 5.0F);
          case "getUniqueId" -> VIEWER;
          case "hasPermission" -> true;
          case "getGameMode" -> GameMode.SURVIVAL;
          case "getInventory" -> inventory;
          case "getName" -> "refresher";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[refresher]";
          default -> throw new UnsupportedOperationException(
              "the refresh loop touched Player#" + method.getName());
        });
  }
}
