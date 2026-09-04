package art.arcane.gloss.preview;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.preview.doc.PreviewFakes;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization pins for THE PADLOCK-CARD TRAP (plan hard constraint 3): a viewer without
 * {@code gloss.preview} must still receive the locked padlock card.
 *
 * <p>The pinned chain is {@code ContainerPreviewAccess.capture} → {@code previewPermitted=false}
 * → {@code canOpen(..)==false} → {@code ContainerPreview.locked(..)} → a card with
 * {@code showsContents==false} whose {@code canView()} is TRUE precisely because it shows no
 * contents. If any lane short-circuits unpermitted viewers out of this chain, these pins fail.
 * The upstream half — the raycast being permission-blind — is pinned by
 * {@code CharacterizationPreviewRaycastTest}.
 */
public class CharacterizationPadlockCardTest {

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private Gloss previousInstance;

  @Before
  public void installBarePlugin() throws ReflectiveOperationException, IOException {
    Gloss gloss = CharacterizationSupport.bareGloss(
        CharacterizationSupport.server(java.util.Map.of()));
    CharacterizationSupport.setField(gloss, "previewRegistry",
        new PreviewDocumentRegistry(temp.newFolder("preview-docs")));
    previousInstance = CharacterizationSupport.installGloss(gloss);
  }

  @After
  public void restore() {
    CharacterizationSupport.restoreGloss(previousInstance);
  }

  @Test
  public void captureRecordsTheMissingPermission() {
    ContainerPreviewAccess.ViewerAccess access = ContainerPreviewAccess.capture(unpermittedPlayer());

    assertFalse(access.previewPermitted());
  }

  @Test
  public void anUnpermittedViewerCannotOpenABlockOrEntity() {
    Player viewer = unpermittedPlayer();
    ContainerPreviewAccess.ViewerAccess access = ContainerPreviewAccess.capture(viewer);

    assertFalse(ContainerPreviewAccess.canOpen(viewer, chestBlock(), access));
    assertFalse(ContainerPreviewAccess.canOpen(viewer,
        PreviewFakes.entity(org.bukkit.entity.EntityType.CHEST_MINECART).build(), access));
  }

  @Test
  public void theLockedCardExistsAndIsViewableWithoutThePermission() {
    Player viewer = unpermittedPlayer();
    Block chest = chestBlock();

    ContainerPreview locked = ContainerPreview.locked(chest, viewer);

    assertNotNull("the shipped locked.json document must always produce a padlock card", locked);
    assertTrue("the padlock card must be viewable by the unpermitted viewer: showsContents is "
        + "false, so canView() never consults gloss.preview", locked.canView());
    assertTrue(locked.matchesBlock(chest));
    assertFalse("canView() must not depend on the viewer holding gloss.preview",
        ContainerPreviewAccess.canView(viewer));
  }

  @Test
  public void aContentsPreviewIsNotViewableWithoutThePermission() throws ReflectiveOperationException {
    // The counterpart pin: the SAME viewer with a contents-showing preview is rejected. Together
    // with the test above this fixes the meaning of showsContents — remove the padlock special
    // case and one of the two fails.
    Player viewer = unpermittedPlayer();
    ContainerPreview showsContents = barePreview(viewer, true);
    ContainerPreview locked = barePreview(viewer, false);

    assertFalse(showsContents.canView());
    assertTrue(locked.canView());
  }

  /** The private constructor, target-less with no elements: exactly the canView() surface. */
  private static ContainerPreview barePreview(Player viewer, boolean showsContents)
      throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, List.class,
        boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(viewer, null, null, new Vector(0.5D, 0.5D, 0.5D),
        List.<PreviewElement>of(), List.of(), showsContents);
  }

  /** A chest block that also answers {@code getLocation()}, which {@code locked(..)} anchors on. */
  private static Block chestBlock() {
    World world = PreviewFakes.chest(27).world();
    return (Block) CharacterizationSupport.proxy(new Class<?>[]{Block.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getType" -> Material.CHEST;
          case "getWorld" -> world;
          case "getLocation" -> new org.bukkit.Location(world, 10.0D, 64.0D, 10.0D);
          case "getX" -> 10;
          case "getY" -> 64;
          case "getZ" -> 10;
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }

  private static Player unpermittedPlayer() {
    ItemStack mainHand = PreviewFakes.stack(Material.STICK, 1);
    PlayerInventory inventory = (PlayerInventory) CharacterizationSupport.proxy(
        new Class<?>[]{PlayerInventory.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getItemInMainHand" -> mainHand;
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "hasPermission" -> false;
          case "getGameMode" -> GameMode.SURVIVAL;
          case "getInventory" -> inventory;
          case "getName" -> "unpermitted";
          case "getUniqueId" -> java.util.UUID.fromString("00000000-0000-0000-0000-00000000ca7d");
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[unpermitted]";
          default -> throw new UnsupportedOperationException(
              "the padlock chain touched Player#" + method.getName());
        });
  }
}
