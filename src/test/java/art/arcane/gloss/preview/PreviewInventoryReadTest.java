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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A refresh reads the container once, not once per slot. CraftBukkit builds a fresh stack mirror
 * for every {@code getItem}, so a 27-slot card was allocating 27 of them every fourth tick per
 * viewer purely to find out that nothing had changed.
 *
 * <p>Visuals are forced hidden (scale factor 0) so the loop runs headlessly, as
 * {@code PreviewSlotRefreshGateTest} does.
 */
public class PreviewInventoryReadTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-00000000f0f2");
  private static final int SLOTS = 27;

  private PreviewFakes.InventoryFake inventoryFake;
  private ContainerPreview preview;

  @Before
  public void openHiddenPreviewOverAWholeChest() throws ReflectiveOperationException {
    scaleFactors().put(VIEWER, 0.0D);
    inventoryFake = PreviewFakes.inventory(SLOTS)
        .item(0, PreviewFakes.stack(Material.STICK, 3))
        .item(13, PreviewFakes.stack(Material.ARROW, 12));
    Inventory inventory = inventoryFake.build();
    List<PreviewElement> elements = new ArrayList<>(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) {
      elements.add(new PreviewElement.Slot(slot, 0, 0, 18, 0, inventory, slot, null, null));
    }
    preview = preview(elements);
    preview.open();
  }

  @After
  public void restoreScaleFactors() throws ReflectiveOperationException {
    scaleFactors().remove(VIEWER);
  }

  @Test
  public void aRefreshReadsTheContainerOnceRatherThanOncePerSlot() {
    int seeded = inventoryFake.calls("getItem");

    refresh();

    assertEquals("the refresh pass must take one contents read", 1, inventoryFake.calls("getContents"));
    assertEquals("and no per-slot reads on top of it", seeded, inventoryFake.calls("getItem"));
  }

  @Test
  public void theSlotsStillSeeWhatTheContainerHolds() throws ReflectiveOperationException {
    refresh();

    assertEquals(Material.STICK, pendingItem(0).getType());
    assertEquals(3, pendingItem(0).getAmount());
    assertEquals(Material.ARROW, pendingItem(13).getType());
    assertEquals(12, pendingItem(13).getAmount());
    assertNull(pendingItem(1));

    inventoryFake.item(13, PreviewFakes.stack(Material.ARROW, 40));
    refresh();
    assertEquals(40, pendingItem(13).getAmount());

    inventoryFake.item(13, null);
    refresh();
    assertNull("an emptied slot publishes nothing", pendingItem(13));
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

  private ItemStack pendingItem(int index) throws ReflectiveOperationException {
    List<?> rendered = (List<?>) CharacterizationSupport.getField(preview, "rendered");
    return (ItemStack) CharacterizationSupport.getField(rendered.get(index), "pendingItem");
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, Double> scaleFactors() throws ReflectiveOperationException {
    Field field = PreviewScaleService.class.getDeclaredField("factors");
    field.setAccessible(true);
    return (Map<UUID, Double>) field.get(null);
  }

  private static ContainerPreview preview(List<PreviewElement> elements)
      throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(viewer(), null, null, new Vector(0.5D, 65.5D, 0.5D),
        List.copyOf(elements), List.of(), true);
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
          case "getName" -> "chestwatcher";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[chestwatcher]";
          default -> throw new UnsupportedOperationException(
              "the refresh loop touched Player#" + method.getName());
        });
  }
}
