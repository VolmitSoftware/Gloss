package art.arcane.gloss.preview;

import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.preview.doc.PreviewFakes;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * A preview refresh copies a slot only when the slot changed. The deep clone is the expensive half
 * of the four-tick refresh, and it was unconditional; the gate is the comparison
 * {@code ItemStack.equals} itself makes, so an unchanged slot keeps publishing the value it already
 * published and the apply side still sends nothing.
 *
 * <p>Visuals are forced hidden (scale factor 0) so the loop runs headlessly, exactly as
 * {@code CharacterizationPreviewRefreshTest} does.
 */
public class PreviewSlotRefreshGateTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-00000000f0f1");

  private PreviewFakes.InventoryFake inventoryFake;
  private ContainerPreview preview;

  @Before
  public void openHiddenPreviewOverAStockedSlot() throws ReflectiveOperationException {
    scaleFactors().put(VIEWER, 0.0D);
    inventoryFake = PreviewFakes.inventory(27).item(0, PreviewFakes.stack(Material.STICK, 3));
    Inventory inventory = inventoryFake.build();
    preview = preview(new PreviewElement.Slot(0, 0, 0, 18, 0, inventory, 0));
    preview.open();
  }

  @After
  public void restoreScaleFactors() throws ReflectiveOperationException {
    scaleFactors().remove(VIEWER);
  }

  @Test
  public void anUnchangedSlotIsNeverClonedAgain() throws ReflectiveOperationException {
    ItemStack seeded = pendingItem();

    refresh();
    assertSame("an unchanged slot must publish the stack it already published", seeded, pendingItem());
    refresh();
    assertSame("and must keep publishing it across refreshes", seeded, pendingItem());
  }

  @Test
  public void aChangedMaterialOrCountIsCopied() throws ReflectiveOperationException {
    ItemStack seeded = pendingItem();

    inventoryFake.item(0, PreviewFakes.stack(Material.ARROW, 3));
    refresh();
    ItemStack swapped = pendingItem();
    assertNotSame("a different material must be copied", seeded, swapped);
    assertEquals(Material.ARROW, swapped.getType());
    assertEquals(3, swapped.getAmount());

    inventoryFake.item(0, PreviewFakes.stack(Material.ARROW, 9));
    refresh();
    ItemStack restacked = pendingItem();
    assertNotSame("a different count must be copied", swapped, restacked);
    assertEquals(9, restacked.getAmount());

    inventoryFake.item(0, null);
    refresh();
    assertNull("an emptied slot publishes nothing", pendingItem());
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  /** One full refresh cycle: tick 0 refreshes contents, and every fourth tick after it. */
  private void refresh() {
    for (int tick = 0; tick < 4; tick++) {
      preview.tick();
    }
  }

  private ItemStack pendingItem() throws ReflectiveOperationException {
    List<?> rendered = (List<?>) CharacterizationSupport.getField(preview, "rendered");
    return (ItemStack) CharacterizationSupport.getField(rendered.get(0), "pendingItem");
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, Double> scaleFactors() throws ReflectiveOperationException {
    Field field = PreviewScaleService.class.getDeclaredField("factors");
    field.setAccessible(true);
    return (Map<UUID, Double>) field.get(null);
  }

  private static ContainerPreview preview(PreviewElement element) throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(viewer(), null, null, new Vector(0.5D, 65.5D, 0.5D),
        List.of(element), true);
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
          case "getName" -> "slotwatcher";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[slotwatcher]";
          default -> throw new UnsupportedOperationException(
              "the refresh loop touched Player#" + method.getName());
        });
  }
}
