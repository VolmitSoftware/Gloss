package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.preview.doc.PreviewFakes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Characterization pins for {@code MenuSessionManager.getLookedAtPreviewTarget} — the preview
 * look-at raycast (plan items T3/C1 and hard constraint 3).
 *
 * <p><b>THE PADLOCK-CARD TRAP (enforced):</b> the raycast is permission-blind. A player without
 * {@code gloss.preview} still acquires a preview target; the permission decides only whether the
 * downstream card is the locked padlock one. If anyone ever permission-gates the raycast (returns
 * no target for unpermitted viewers), {@link #raycastIsPermissionBlindForAnUnpermittedViewer}
 * MUST fail. See {@code CharacterizationPadlockCardTest} for the downstream half of the trap.
 *
 * <p>The fake world honors the {@code maxDistance} and entity-filter arguments it is given, so
 * these pins stay valid when the entity trace is capped at the block-hit distance or skipped for
 * matcher-less snapshots — those optimizations are outcome-identical by design.
 */
public class CharacterizationPreviewRaycastTest {

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  private Gloss previousInstance;
  private MenuSessionManager manager;
  private ScriptedWorld world;

  @Before
  public void installBarePlugin() throws ReflectiveOperationException, IOException {
    Gloss gloss = CharacterizationSupport.bareGloss(
        CharacterizationSupport.server(java.util.Map.of()));
    CharacterizationSupport.setField(gloss, "previewRegistry",
        new PreviewDocumentRegistry(temp.newFolder("preview-docs")));
    previousInstance = CharacterizationSupport.installGloss(gloss);
    manager = new MenuSessionManager();
    world = new ScriptedWorld();
  }

  @After
  public void restore() {
    CharacterizationSupport.restoreGloss(previousInstance);
  }

  // ---------------------------------------------------------------------
  // The trap
  // ---------------------------------------------------------------------

  @Test
  public void raycastIsPermissionBlindForAnUnpermittedViewer() {
    Block chest = PreviewFakes.chest(27).build();
    world.blockHit(chest, 3.0D);
    AtomicInteger permissionChecks = new AtomicInteger();
    Player unpermitted = player(world.bukkit(), permissionChecks);

    Object target = lookedAtTarget(unpermitted);

    assertNotNull("the preview raycast must NEVER be permission-gated: an unpermitted viewer "
        + "still acquires the target (the permission only selects the locked padlock card later)",
        target);
    assertSame(chest, block(target));
  }

  // ---------------------------------------------------------------------
  // Nearer-wins and occlusion
  // ---------------------------------------------------------------------

  @Test
  public void nearerPreviewBlockBeatsFartherEntity() {
    Block chest = PreviewFakes.chest(27).build();
    world.blockHit(chest, 2.0D);
    world.entityHit(storageMinecart(), 3.0D);

    Object target = lookedAtTarget(player(world.bukkit(), new AtomicInteger()));

    assertSame(chest, block(target));
    assertNull(entity(target));
  }

  @Test
  public void nearerEntityBeatsFartherPreviewBlock() {
    Entity minecart = storageMinecart();
    world.blockHit(PreviewFakes.chest(27).build(), 4.0D);
    world.entityHit(minecart, 2.0D);

    Object target = lookedAtTarget(player(world.bukkit(), new AtomicInteger()));

    assertSame(minecart, entity(target));
    assertNull(block(target));
  }

  @Test
  public void equalDistancesResolveToTheEntity() {
    Entity minecart = storageMinecart();
    world.blockHit(PreviewFakes.chest(27).build(), 3.0D);
    world.entityHit(minecart, 3.0D);

    Object target = lookedAtTarget(player(world.bukkit(), new AtomicInteger()));

    assertSame("the +0.01 squared-distance tie-break requires the block to be strictly nearer; "
        + "a dead tie goes to the entity", minecart, entity(target));
  }

  @Test
  public void aNearerNonPreviewBlockOccludesTheEntityBehindIt() {
    world.blockHit(PreviewFakes.block(org.bukkit.Material.STONE).build(), 2.0D);
    world.entityHit(storageMinecart(), 5.0D);

    assertNull("an entity behind a nearer solid non-preview block must not be acquired",
        lookedAtTarget(player(world.bukkit(), new AtomicInteger())));
  }

  @Test
  public void anEntityInFrontOfTheOccluderIsStillAcquired() {
    Entity minecart = storageMinecart();
    world.blockHit(PreviewFakes.block(org.bukkit.Material.STONE).build(), 5.0D);
    world.entityHit(minecart, 2.0D);

    Object target = lookedAtTarget(player(world.bukkit(), new AtomicInteger()));

    assertSame(minecart, entity(target));
  }

  @Test
  public void anEntityAloneIsAcquiredWithoutAnyBlockHit() {
    Entity minecart = storageMinecart();
    world.entityHit(minecart, 6.0D);

    Object target = lookedAtTarget(player(world.bukkit(), new AtomicInteger()));

    assertSame(minecart, entity(target));
  }

  @Test
  public void anExplicitlyMatchedFurnaceMinecartIsAcquired() {
    Entity minecart = PreviewFakes.entity(EntityType.FURNACE_MINECART)
        .as(PoweredMinecart.class)
        .build();
    world.entityHit(minecart, 4.0D);

    Object target = lookedAtTarget(player(world.bukkit(), new AtomicInteger()));

    assertSame(minecart, entity(target));
  }

  @Test
  public void aNonPreviewBlockAloneYieldsNoTarget() {
    world.blockHit(PreviewFakes.block(org.bukkit.Material.STONE).build(), 2.0D);

    assertNull(lookedAtTarget(player(world.bukkit(), new AtomicInteger())));
  }

  @Test
  public void nothingWithinLookDistanceYieldsNoTarget() {
    assertNull(lookedAtTarget(player(world.bukkit(), new AtomicInteger())));
  }

  @Test
  public void hitsBeyondTheConfiguredLookDistanceAreNotAcquired() {
    // Default previews.lookDistance is 10.0; the scripted world honours maxDistance.
    world.blockHit(PreviewFakes.chest(27).build(), 11.0D);
    world.entityHit(storageMinecart(), 12.0D);

    assertNull(lookedAtTarget(player(world.bukkit(), new AtomicInteger())));
  }

  @Test
  public void disabledPreviewsYieldNoTargetEvenWhenLookingAtAChest() throws ReflectiveOperationException {
    Block chest = PreviewFakes.chest(27).build();
    world.blockHit(chest, 3.0D);
    GlossConfigFile file = new GlossConfigFile();
    file.features.previews = false;
    file.normalize();
    CharacterizationSupport.setField(Gloss.instance, "config", GlossConfig.from(file));
    try {
      assertNull(lookedAtTarget(player(world.bukkit(), new AtomicInteger())));
    } finally {
      CharacterizationSupport.setField(Gloss.instance, "config", null);
    }
  }

  @Test
  public void theEntityTraceOnlyEverSeesRegistryEligibleEntities() {
    // A zombie is never preview-eligible: the filter handed to the world must reject it, so no
    // target may come back even though the scripted ray "hits" it.
    world.entityHit(PreviewFakes.entity(EntityType.ZOMBIE).build(), 2.0D);

    assertNull(lookedAtTarget(player(world.bukkit(), new AtomicInteger())));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private Object lookedAtTarget(Player player) {
    return CharacterizationSupport.invoke(manager, "getLookedAtPreviewTarget",
        new Class<?>[]{Player.class}, player);
  }

  private static Block block(Object target) {
    return (Block) component(target, "block");
  }

  private static Entity entity(Object target) {
    return (Entity) component(target, "entity");
  }

  private static Object component(Object target, String name) {
    assertNotNull("expected a preview target", target);
    try {
      Method accessor = target.getClass().getDeclaredMethod(name);
      accessor.setAccessible(true);
      return accessor.invoke(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("PreviewTarget#" + name + " is part of a characterization pin", failure);
    }
  }

  private static Entity storageMinecart() {
    return PreviewFakes.entity(EntityType.CHEST_MINECART).as(StorageMinecart.class).build();
  }

  /**
   * A player at the origin looking along +Z whose {@code hasPermission} always answers false —
   * and counts. The raycast must neither consult the permission nor be influenced by it.
   */
  private static Player player(World world, AtomicInteger permissionChecks) {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getEyeLocation" -> new Location(world, 0.0D, 65.0D, 0.0D, 0.0F, 0.0F);
          case "hasPermission" -> {
            permissionChecks.incrementAndGet();
            yield false;
          }
          case "getName" -> "unpermitted";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[unpermitted]";
          default -> throw new UnsupportedOperationException(
              "the raycast touched Player#" + method.getName());
        });
  }

  /**
   * A world whose two ray traces are scripted by distance along the eye ray. Both honour the
   * {@code maxDistance} they are given, and the entity trace honours the eligibility filter —
   * exactly like the real traces — so outcome-identical raycast optimizations keep these pins
   * green while behavioral changes (permission gates, occlusion changes) break them.
   */
  private static final class ScriptedWorld {
    private Block hitBlock;
    private double blockDistance;
    private Entity hitEntity;
    private double entityDistance;
    private final World bukkit;

    private ScriptedWorld() {
      this.bukkit = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "rayTraceBlocks" -> traceBlocks((Location) args[0], (Vector) args[1], (Double) args[2]);
            case "rayTraceEntities" -> traceEntities((Location) args[0], (Vector) args[1],
                (Double) args[2], args[args.length - 1]);
            case "getName" -> "scripted";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "World[scripted]";
            default -> throw new UnsupportedOperationException(
                "the raycast touched World#" + method.getName());
          });
    }

    private World bukkit() {
      return bukkit;
    }

    private void blockHit(Block block, double distance) {
      this.hitBlock = block;
      this.blockDistance = distance;
    }

    private void entityHit(Entity entity, double distance) {
      this.hitEntity = entity;
      this.entityDistance = distance;
    }

    private RayTraceResult traceBlocks(Location start, Vector direction, double maxDistance) {
      if (hitBlock == null || blockDistance > maxDistance) {
        return null;
      }
      return new RayTraceResult(position(start, direction, blockDistance), hitBlock, null);
    }

    @SuppressWarnings("unchecked")
    private RayTraceResult traceEntities(Location start, Vector direction, double maxDistance,
                                         Object filter) {
      if (hitEntity == null || entityDistance > maxDistance) {
        return null;
      }
      if (filter instanceof Predicate<?> predicate
          && !((Predicate<Entity>) predicate).test(hitEntity)) {
        return null;
      }
      return new RayTraceResult(position(start, direction, entityDistance), hitEntity, null);
    }

    private static Vector position(Location start, Vector direction, double distance) {
      return start.toVector().add(direction.clone().normalize().multiply(distance));
    }
  }
}
