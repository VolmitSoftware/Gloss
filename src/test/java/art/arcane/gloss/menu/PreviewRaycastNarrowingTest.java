package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.preview.doc.PreviewFakes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins that the look-at scan actually narrows (plan item T3/C1). The outcomes are pinned by
 * {@code CharacterizationPreviewRaycastTest}; this suite pins the work that no longer happens —
 * the entity sweep is held to the span where an entity could still beat the block, and is skipped
 * outright when no document declares an entity matcher.
 */
public class PreviewRaycastNarrowingTest {

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private Gloss previousInstance;
  private Gloss gloss;
  private MenuSessionManager manager;
  private RecordingWorld world;

  @Before
  public void installBarePlugin() throws ReflectiveOperationException, IOException {
    gloss = CharacterizationSupport.bareGloss(CharacterizationSupport.server(java.util.Map.of()));
    previousInstance = CharacterizationSupport.installGloss(gloss);
    manager = new MenuSessionManager();
    world = new RecordingWorld();
  }

  @After
  public void restore() {
    CharacterizationSupport.restoreGloss(previousInstance);
  }

  @Test
  public void theEntitySweepIsCappedWhereABlockWouldWinAnyway() throws ReflectiveOperationException {
    withRegistry();
    world.blockHit(PreviewFakes.chest(27).build(), 2.0D);
    world.entityHit(PreviewFakes.entity(EntityType.CHEST_MINECART).as(StorageMinecart.class).build(), 9.0D);

    assertNotNull(scan());

    assertEquals("the entity trace must run exactly once", 1, world.entityReach.size());
    double reach = world.entityReach.getFirst();
    assertEquals("the cap is the linear form of the +0.01 squared tie-break",
        Math.sqrt(4.01D), reach, 1.0E-12D);
    assertTrue("and it must be far shorter than the configured look distance", reach < 3.0D);
  }

  @Test
  public void withNoBlockHitTheSweepStillReachesTheFullLookDistance() throws ReflectiveOperationException {
    withRegistry();
    world.entityHit(PreviewFakes.entity(EntityType.CHEST_MINECART).as(StorageMinecart.class).build(), 9.0D);

    assertNotNull(scan());

    assertEquals(1, world.entityReach.size());
    assertEquals(10.0D, world.entityReach.getFirst(), 0.0D);
  }

  @Test
  public void aNonPreviewBlockStillCapsTheSweepThatItOccludes() throws ReflectiveOperationException {
    withRegistry();
    world.blockHit(PreviewFakes.block(Material.STONE).build(), 2.0D);
    world.entityHit(PreviewFakes.entity(EntityType.CHEST_MINECART).as(StorageMinecart.class).build(), 5.0D);

    assertNull("an entity behind a solid block is occluded, so the sweep may stop at the block",
        scan());
    assertEquals(Math.sqrt(4.01D), world.entityReach.getFirst(), 1.0E-12D);
  }

  @Test
  public void withoutEntityMatchersTheSweepNeverRuns() {
    // No registry installed: nothing declares an entity matcher, so the filter would reject
    // everything the sweep could find.
    world.entityHit(PreviewFakes.entity(EntityType.CHEST_MINECART).as(StorageMinecart.class).build(), 3.0D);

    scan();

    assertTrue("a matcher-less snapshot must not pay for an entity sweep", world.entityReach.isEmpty());
    assertEquals("the block trace still runs", 1, world.blockReach.size());
  }

  @Test
  public void aPlayerWithNothingOpenStillOnlyPaysTheEntitySweepOncePerInterval()
      throws ReflectiveOperationException {
    withRegistry();
    // A non-preview block, so no target is acquired and no holder is ever created: this is the
    // walking-around population the cadence exists for.
    world.blockHit(PreviewFakes.block(Material.STONE).build(), 2.0D);
    AtomicReference<Location> eye = new AtomicReference<>();
    Player player = scanningPlayer(eye);

    for (int scan = 0; scan < 8; scan++) {
      eye.set(new Location(world.bukkit(), 0.0D, 65.0D, scan, 0.0F, 0.0F));
      manager.managePreviewEvents(player, false);
    }

    assertEquals("the block trace answers on every scan", 8, world.blockReach.size());
    assertEquals("the entity sweep is granted once every four scans, holder or no holder",
        2, world.entityReach.size());
  }

  @Test
  public void aForcedRescanAlwaysSweeps() throws ReflectiveOperationException {
    withRegistry();
    world.blockHit(PreviewFakes.block(Material.STONE).build(), 2.0D);
    AtomicReference<Location> eye = new AtomicReference<>();
    Player player = scanningPlayer(eye);

    for (int scan = 0; scan < 4; scan++) {
      eye.set(new Location(world.bukkit(), 0.0D, 65.0D, scan, 0.0F, 0.0F));
      manager.managePreviewEvents(player, true);
    }

    assertEquals("the fallback sweep must never be held back by the cadence",
        4, world.entityReach.size());
  }

  @Test
  public void aDepartedPlayerLeavesNoTraceBookkeepingBehind() throws ReflectiveOperationException {
    withRegistry();
    world.blockHit(PreviewFakes.block(Material.STONE).build(), 2.0D);
    AtomicReference<Location> eye = new AtomicReference<>();
    Player player = scanningPlayer(eye);
    eye.set(new Location(world.bukkit(), 0.0D, 65.0D, 0.0D, 0.0F, 0.0F));
    manager.managePreviewEvents(player, false);
    eye.set(new Location(world.bukkit(), 0.0D, 65.0D, 1.0D, 0.0F, 0.0F));
    manager.managePreviewEvents(player, false);
    assertEquals(1, world.entityReach.size());

    manager.forgetPlayer(player);

    eye.set(new Location(world.bukkit(), 0.0D, 65.0D, 2.0D, 0.0F, 0.0F));
    manager.managePreviewEvents(player, false);
    assertEquals("a rejoining player starts a fresh interval rather than inheriting a countdown",
        2, world.entityReach.size());
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private Player scanningPlayer(AtomicReference<Location> eye) {
    UUID playerId = UUID.fromString("00000000-0000-0000-0000-00000000ca57");
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getEyeLocation" -> eye.get().clone();
          case "getUniqueId" -> playerId;
          case "isOnline" -> true;
          case "hasPermission" -> false;
          case "getName" -> "scanning";
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }

  private void withRegistry() throws ReflectiveOperationException {
    try {
      CharacterizationSupport.setField(gloss, "previewRegistry",
          new PreviewDocumentRegistry(temp.newFolder("preview-docs")));
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private Object scan() {
    return CharacterizationSupport.invoke(manager, "getLookedAtPreviewTarget",
        new Class<?>[]{Player.class}, player());
  }

  private Player player() {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getEyeLocation" -> new Location(world.bukkit(), 0.0D, 65.0D, 0.0D, 0.0F, 0.0F);
          case "hasPermission" -> false;
          case "getName" -> "narrowing";
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }

  /** Scripts both traces by distance and records the reach each was asked for. */
  private static final class RecordingWorld {
    private final List<Double> blockReach = new ArrayList<>();
    private final List<Double> entityReach = new ArrayList<>();
    private Block hitBlock;
    private double blockDistance;
    private org.bukkit.entity.Entity hitEntity;
    private double entityDistance;
    private final World bukkit;

    private RecordingWorld() {
      this.bukkit = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "rayTraceBlocks" -> {
              blockReach.add((Double) args[2]);
              yield trace((Location) args[0], (Vector) args[1], (Double) args[2], hitBlock, blockDistance, null);
            }
            case "rayTraceEntities" -> {
              entityReach.add((Double) args[2]);
              yield trace((Location) args[0], (Vector) args[1], (Double) args[2], null, entityDistance,
                  args[args.length - 1]);
            }
            case "getName" -> "recording";
            default -> CharacterizationSupport.identity(proxy, method, args);
          });
    }

    private World bukkit() {
      return bukkit;
    }

    private void blockHit(Block block, double distance) {
      this.hitBlock = block;
      this.blockDistance = distance;
    }

    private void entityHit(org.bukkit.entity.Entity entity, double distance) {
      this.hitEntity = entity;
      this.entityDistance = distance;
    }

    @SuppressWarnings("unchecked")
    private RayTraceResult trace(Location start, Vector direction, double maxDistance, Block block,
                                 double distance, Object filter) {
      Object hit = block == null ? hitEntity : block;
      if (hit == null || distance > maxDistance) {
        return null;
      }
      if (filter instanceof Predicate<?> predicate
          && !((Predicate<org.bukkit.entity.Entity>) predicate).test(hitEntity)) {
        return null;
      }
      Vector position = start.toVector().add(direction.clone().normalize().multiply(distance));
      return block == null
          ? new RayTraceResult(position, hitEntity, null)
          : new RayTraceResult(position, block, null);
    }
  }
}
