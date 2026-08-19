package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.preview.ContainerPreview;
import art.arcane.gloss.preview.PreviewElement;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Characterization pins for the preview half of the session-holder lifecycle (plan item T16/C2):
 * a holder is created when a preview opens, replacing a preview closes the old one, a preview
 * that stops being viewable is closed by the holder tick, and quit/shutdown teardown leaves no
 * open previews behind. Outcomes are observed through {@link GlossTelemetry#previewsOpen()} and
 * the public holder/manager surface — never through dispatch counts — so re-keying the holder map
 * or pruning idle holders stays invisible to these pins as long as the lifecycle outcomes hold.
 *
 * <p>The menu-session half of the holder lifecycle is already pinned by
 * {@code SessionHolderSnapshotTest}; these tests deliberately cover only what that suite does not.
 */
public class CharacterizationSessionHolderLifecycleTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000ab1e");

  @Before
  public void resetTelemetry() {
    GlossTelemetry.clear();
  }

  @After
  public void clearTelemetry() {
    GlossTelemetry.clear();
  }

  @Test
  public void openingAPreviewOverAnotherClosesTheFirst() throws ReflectiveOperationException {
    SessionHolder holder = new SessionHolder(player(new AtomicBoolean(true), true),
        new PlayerSnapshotStore<>());

    holder.openPreview(emptyPreview(player(new AtomicBoolean(true), true), false));
    assertEquals(1, GlossTelemetry.previewsOpen());
    assertTrue(holder.hasPreview());

    holder.openPreview(emptyPreview(player(new AtomicBoolean(true), true), false));

    assertEquals("replacing a preview must close the replaced one — never leak it",
        1, GlossTelemetry.previewsOpen());
    assertTrue(holder.hasPreview());

    holder.closePreview();
    assertEquals(0, GlossTelemetry.previewsOpen());
    assertFalse(holder.hasPreview());
  }

  @Test
  public void aPreviewThatStopsBeingViewableIsClosedByTheTick() throws ReflectiveOperationException {
    Player viewer = player(new AtomicBoolean(true), false);
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    // showsContents=true + no gloss.preview permission => canView() false: the tick must evict it.
    holder.openPreview(emptyPreview(viewer, true));
    assertEquals(1, GlossTelemetry.previewsOpen());

    boolean removable = holder.tick();

    assertFalse("an online player's holder is not removable", removable);
    assertFalse("a preview whose canView() turned false must be closed by the tick",
        holder.hasPreview());
    assertEquals(0, GlossTelemetry.previewsOpen());
  }

  @Test
  public void quitTeardownClosesThePreview() throws ReflectiveOperationException {
    Player viewer = player(new AtomicBoolean(true), true);
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    holder.openPreview(emptyPreview(viewer, false));

    holder.close(HoloCloseReason.QUIT);

    assertFalse(holder.hasPreview());
    assertEquals(0, GlossTelemetry.previewsOpen());
  }

  @Test
  public void anOfflinePlayersTickTearsDownAndReportsRemovable() throws ReflectiveOperationException {
    AtomicBoolean online = new AtomicBoolean(true);
    Player viewer = player(online, true);
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    holder.openPreview(emptyPreview(viewer, false));
    online.set(false);

    assertTrue("an offline player's holder must report itself removable", holder.tick());
    assertFalse(holder.hasPreview());
    assertEquals(0, GlossTelemetry.previewsOpen());
  }

  @Test
  public void theManagerCreatesAndTearsDownHoldersAroundPreviews() throws Exception {
    Gloss previous = CharacterizationSupport.installGloss(
        CharacterizationSupport.bareGloss(CharacterizationSupport.server(java.util.Map.of())));
    try {
      MenuSessionManager manager = new MenuSessionManager();
      Player viewer = player(new AtomicBoolean(true), true);
      assertEquals(0, manager.holderCount());
      assertFalse(manager.hasPreviewSession(viewer));

      manager.addPreviewSession(viewer, emptyPreview(viewer, false));

      assertEquals("opening a preview creates the player's holder", 1, manager.holderCount());
      assertTrue(manager.hasPreviewSession(viewer));
      assertEquals(1, GlossTelemetry.previewsOpen());

      manager.destroyAll();

      assertEquals("shutdown must clear every holder", 0, manager.holderCount());
      assertFalse(manager.hasPreviewSession(viewer));
      assertEquals(0, GlossTelemetry.previewsOpen());
    } finally {
      CharacterizationSupport.restoreGloss(previous);
    }
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  /** An element-less preview: the full lifecycle runs without ever reaching the packet layer. */
  private static ContainerPreview emptyPreview(Player viewer, boolean showsContents)
      throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(viewer, null, null, new Vector(0.5D, 65.5D, 0.5D),
        List.<PreviewElement>of(), showsContents);
  }

  private static Player player(AtomicBoolean online, boolean permitted) {
    ItemStack mainHand = art.arcane.gloss.preview.doc.PreviewFakes.stack(Material.STICK, 1);
    PlayerInventory inventory = (PlayerInventory) CharacterizationSupport.proxy(
        new Class<?>[]{PlayerInventory.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getItemInMainHand" -> mainHand;
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> PLAYER;
          case "isOnline" -> online.get();
          case "hasPermission" -> permitted;
          case "getGameMode" -> GameMode.SURVIVAL;
          case "getInventory" -> inventory;
          case "getEyeLocation" -> new Location(null, 0.0D, 66.62D, 2.0D, 0.0F, 0.0F);
          case "getLocation" -> new Location(null, 0.0D, 65.0D, 2.0D, 0.0F, 0.0F);
          case "getName" -> "holderling";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[holderling]";
          default -> throw new UnsupportedOperationException(
              "the holder lifecycle touched Player#" + method.getName());
        });
  }
}
