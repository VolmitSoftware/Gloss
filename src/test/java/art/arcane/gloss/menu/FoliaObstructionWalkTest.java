package art.arcane.gloss.menu;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@code MenuSessionManager.isVoxelObstructed} — Folia's stand-in for a block ray trace, and
 * the T9/C9 correctness fix.
 *
 * <p>The bug it fixes: the old walk treated a voxel the calling region does not own as a solid
 * obstruction, so a menu placed such that the eye ray leaves the clicker's region before reaching
 * the button was silently unclickable. The pin is
 * {@link #aSolidBlockInAForeignRegionDoesNotObstruct}.
 */
public class FoliaObstructionWalkTest {

  @Test
  public void aClearLineOfSightIsNotObstructed() {
    ScriptedWorld world = new ScriptedWorld();

    assertFalse(walk(world, alongZ(), 6.0D, everythingOwned()));
  }

  @Test
  public void aSolidBlockOnTheRayObstructs() {
    ScriptedWorld world = new ScriptedWorld();
    world.solid(0, 65, 3);

    assertTrue(walk(world, alongZ(), 6.0D, everythingOwned()));
  }

  @Test
  public void aSolidBlockInAForeignRegionDoesNotObstruct() {
    ScriptedWorld world = new ScriptedWorld();
    world.solid(0, 65, 3);
    // Chunk 0,0 is the clicker's; the block sits in it but the region is scripted as foreign.
    MenuSessionManager.RegionOwnership none = (w, chunkX, chunkZ) -> false;

    assertFalse("a block the calling region does not own must be treated as passable — otherwise "
        + "a menu straddling a region boundary is silently unclickable",
        walk(world, alongZ(), 6.0D, none));
  }

  @Test
  public void onlyTheForeignSideOfABoundaryIsWaived() {
    ScriptedWorld world = new ScriptedWorld();
    world.solid(0, 65, 3);
    world.solid(0, 65, 20);
    // Chunk 0,0 (z 0..15) is owned; chunk 0,1 (z 16..31) is not.
    MenuSessionManager.RegionOwnership nearOnly = (w, chunkX, chunkZ) -> chunkZ == 0;

    assertTrue("the owned block still obstructs", walk(world, alongZ(), 30.0D, nearOnly));
    world.passable(0, 65, 3);
    assertFalse("only the foreign block is left, and it must not obstruct",
        walk(world, alongZ(), 30.0D, nearOnly));
  }

  @Test
  public void blocksBeyondTheRequestedDistanceAreIgnored() {
    ScriptedWorld world = new ScriptedWorld();
    world.solid(0, 65, 8);

    assertFalse(walk(world, alongZ(), 6.0D, everythingOwned()));
    assertTrue(walk(world, alongZ(), 12.0D, everythingOwned()));
  }

  @Test
  public void blocksOutsideTheBuildHeightAreNeverRead() {
    ScriptedWorld world = new ScriptedWorld();
    Location eye = new Location(world.bukkit(), 0.5D, 318.5D, 0.5D, 0.0F, -90.0F);

    assertFalse("straight up out of the world is not obstructed",
        walk(world, eye, 20.0D, everythingOwned()));
    assertTrue("no block above the build height may be read",
        world.reads.stream().allMatch(key -> key[1] < 320));
  }

  @Test
  public void regionOwnershipIsResolvedOncePerChunkColumn() {
    ScriptedWorld world = new ScriptedWorld();
    Set<Long> columns = new LinkedHashSet<>();
    int[] queries = new int[1];
    MenuSessionManager.RegionOwnership counting = (w, chunkX, chunkZ) -> {
      queries[0]++;
      columns.add((((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL));
      return true;
    };

    walk(world, alongZ(), 40.0D, counting);

    assertEquals("a 40-block walk along +Z crosses three chunk columns and must ask about each once",
        columns.size(), queries[0]);
    assertTrue("the walk must cross more than one column to make this meaningful", columns.size() > 1);
  }

  @Test
  public void aDiagonalRayIsObstructedByABlockOnItsPath() {
    ScriptedWorld world = new ScriptedWorld();
    world.solid(2, 65, 2);
    Location eye = new Location(world.bukkit(), 0.5D, 65.5D, 0.5D, -45.0F, 0.0F);

    assertTrue(walk(world, eye, 8.0D, everythingOwned()));
    world.passable(2, 65, 2);
    assertFalse(walk(world, eye, 8.0D, everythingOwned()));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static boolean walk(ScriptedWorld world, Location eye, double distance,
                              MenuSessionManager.RegionOwnership ownership) {
    world.reads.clear();
    return MenuSessionManager.isVoxelObstructed(world.bukkit(), eye, distance, ownership);
  }

  private Location alongZ() {
    return new Location(null, 0.5D, 65.5D, 0.5D, 0.0F, 0.0F);
  }

  private static MenuSessionManager.RegionOwnership everythingOwned() {
    return (w, chunkX, chunkZ) -> true;
  }

  /** A world of exactly the blocks a test marks solid; everything else is air. */
  private static final class ScriptedWorld {
    private final Set<Long> solids = new HashSet<>();
    private final List<int[]> reads = new ArrayList<>();
    private final World bukkit;

    private ScriptedWorld() {
      this.bukkit = (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getBlockAt" -> block((Integer) args[0], (Integer) args[1], (Integer) args[2]);
            case "getName" -> "scripted";
            default -> CharacterizationSupport.identity(proxy, method, args);
          });
    }

    private World bukkit() {
      return bukkit;
    }

    private void solid(int x, int y, int z) {
      solids.add(key(x, y, z));
    }

    private void passable(int x, int y, int z) {
      solids.remove(key(x, y, z));
    }

    private Block block(int x, int y, int z) {
      reads.add(new int[]{x, y, z});
      boolean passable = !solids.contains(key(x, y, z));
      return (Block) CharacterizationSupport.proxy(new Class<?>[]{Block.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "isPassable" -> passable;
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            default -> CharacterizationSupport.identity(proxy, method, args);
          });
    }

    private static long key(int x, int y, int z) {
      return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
  }
}
