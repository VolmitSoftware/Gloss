package art.arcane.gloss.panel;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code hasCandidate} is the per-player prefilter the panel driver runs every tick, so it answers
 * from a coarse occupancy index rather than by walking the chunk window or every occupied bucket.
 * These pins hold it to the brute-force answer across both cell-selection branches, across
 * incremental maintenance, and at the exact range boundary.
 */
public class PanelSpatialIndexCandidateTest {
  private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-000000000701");
  private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-000000000702");

  @Test
  public void hasCandidateMatchesABruteForceScanAcrossBothCellBranches() {
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(spread());

    assertMatchesBruteForce(index);
  }

  @Test
  public void hasCandidateSurvivesIncrementalMaintenance() {
    List<PanelDefinition> spread = spread();
    PanelSpatialIndex index = new PanelSpatialIndex();
    for (PanelDefinition board : spread) {
      index.upsert(board);
    }
    index.upsert(spread.get(2).withTransform(
        PanelTransform.at("example:world", OVERWORLD, -9000.5D, 64.0D, 12000.0D, 0.0D)));
    index.remove(spread.get(5).uuid());
    index.upsert(spread.get(5));
    // A follow-panel pose lands as an in-place upsert that never leaves its bucket.
    index.upsert(spread.get(1).withTransform(
        PanelTransform.at("example:world", OVERWORLD, 4.0D, 70.0D, 4.0D, 0.0D)));

    assertMatchesBruteForce(index);
  }

  @Test
  public void movingOnePanelLeavesEveryOtherWorldsCellIndexAlone() throws Exception {
    List<PanelDefinition> spread = spread();
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(spread);
    Map<UUID, Map<Long, List<Long>>> before = cells(index);
    Object netherBefore = before.get(NETHER);
    Object overworldBefore = before.get(OVERWORLD);
    long farCell = distantOverworldCell(before);
    Object farCellBefore = before.get(OVERWORLD).get(farCell);

    index.upsert(spread.get(0).withTransform(
        PanelTransform.at("example:world", OVERWORLD, 40.0D, 64.0D, 40.0D, 0.0D)));

    Map<UUID, Map<Long, List<Long>>> after = cells(index);
    assertSame("a world nothing moved in must keep its cell index",
        netherBefore, after.get(NETHER));
    assertSame("a cell the move did not touch must keep its bucket list",
        farCellBefore, after.get(OVERWORLD).get(farCell));
    assertNotSame("the world the panel moved in is re-published",
        overworldBefore, after.get(OVERWORLD));
  }

  @Test
  public void removingTheLastPanelOfAWorldDropsItsCellIndex() throws Exception {
    PanelDefinition only = board("solo", NETHER, 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(board("keep", OVERWORLD, 0.0D, 0.0D), only));
    assertTrue(cells(index).containsKey(NETHER));

    index.remove(only.uuid());

    assertFalse("an emptied world must leave no cell index behind",
        cells(index).containsKey(NETHER));
    assertFalse(index.hasCandidate(NETHER, 0.0D, 0.0D, 256.0D));
  }

  @Test
  public void anEmptyIndexAndAForeignWorldHaveNoCandidates() {
    PanelSpatialIndex index = new PanelSpatialIndex();
    assertFalse(index.hasCandidate(OVERWORLD, 0.0D, 0.0D, 256.0D));

    index.replaceAll(List.of(board("only", OVERWORLD, 0.0D, 0.0D)));
    assertFalse("a world with no placements never has a candidate",
        index.hasCandidate(NETHER, 0.0D, 0.0D, 256.0D));
  }

  @Test
  public void theCandidateBoundaryIsInclusiveJustLikeTheQuery() {
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(board("ring", OVERWORLD, 64.0D, 0.0D)));

    assertEquals(true, index.hasCandidate(OVERWORLD, 0.0D, 0.0D, 64.0D));
    assertEquals(false, index.hasCandidate(OVERWORLD, 0.0D, 0.0D, 63.999D));
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, Map<Long, List<Long>>> cells(PanelSpatialIndex index) throws Exception {
    Field stateField = PanelSpatialIndex.class.getDeclaredField("state");
    stateField.setAccessible(true);
    Object state = stateField.get(index);
    Field cellsField = state.getClass().getDeclaredField("cellsByWorld");
    cellsField.setAccessible(true);
    return (Map<UUID, Map<Long, List<Long>>>) cellsField.get(state);
  }

  /** A cell of the overworld index that a move near the origin cannot touch. */
  private static long distantOverworldCell(Map<UUID, Map<Long, List<Long>>> cells) {
    long farthest = 0L;
    long best = Long.MIN_VALUE;
    for (Long cellKey : cells.get(OVERWORLD).keySet()) {
      long distance = Math.abs((long) (int) (cellKey >> 32)) + Math.abs((long) (int) (long) cellKey);
      if (distance > best) {
        best = distance;
        farthest = cellKey;
      }
    }
    return farthest;
  }

  private static void assertMatchesBruteForce(PanelSpatialIndex index) {
    List<PanelDefinition> placed = index.list();
    double[] radii = {0.0D, 1.0D, 16.0D, 64.0D, 86.7D, 256.0D, 278.7D, 5000.0D, 1.0E7D};
    for (double x = -9100.0D; x <= 9100.0D; x += 517.0D) {
      for (double z = -12100.0D; z <= 12100.0D; z += 733.0D) {
        for (double radius : radii) {
          assertEquals("hasCandidate must match a brute-force scan at (" + x + ", " + z
                  + ") r=" + radius,
              bruteForce(placed, OVERWORLD, x, z, radius),
              index.hasCandidate(OVERWORLD, x, z, radius));
          assertEquals("hasCandidate must match a brute-force scan in the nether at (" + x + ", "
                  + z + ") r=" + radius,
              bruteForce(placed, NETHER, x, z, radius),
              index.hasCandidate(NETHER, x, z, radius));
        }
      }
    }
  }

  private static boolean bruteForce(List<PanelDefinition> placed, UUID worldUuid,
                                    double x, double z, double radius) {
    for (PanelDefinition board : placed) {
      PanelTransform transform = board.transform();
      if (transform.worldUuid().equals(worldUuid)
          && Math.hypot(transform.x() - x, transform.z() - z) <= radius) {
        return true;
      }
    }
    return false;
  }

  /**
   * Placements that straddle coarse cells, share cells, sit on cell edges, and reach far enough
   * apart that a wide query selects the cell-scan branch while a narrow one selects the window.
   */
  private static List<PanelDefinition> spread() {
    List<PanelDefinition> boards = new ArrayList<>();
    double[][] points = {
        {0.0D, 0.0D}, {3.5D, -2.5D}, {255.0D, 255.0D}, {256.0D, 256.0D}, {257.5D, 0.0D},
        {-256.0D, -256.0D}, {-257.0D, 1.0D}, {1024.0D, -1024.0D}, {4096.5D, 4096.5D},
        {-8192.0D, 8192.0D}, {9000.0D, -12000.0D}, {512.0D, 512.0D}, {520.0D, 511.0D},
    };
    int index = 0;
    for (double[] point : points) {
      boards.add(board("spread-" + (index++), OVERWORLD, point[0], point[1]));
    }
    boards.add(board("nether-hub", NETHER, 0.0D, 0.0D));
    boards.add(board("nether-far", NETHER, 3000.0D, -3000.0D));
    return boards;
  }

  private static PanelDefinition board(String id, UUID worldUuid, double x, double z) {
    return new PanelDefinition(PanelDefinition.CURRENT_SCHEMA_VERSION, id,
        UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
        PanelDefinition.INITIAL_REVISION, "menu",
        PanelTransform.at("example:world", worldUuid, x, 64.0D, z, 0.0D),
        PanelFollow.none(), PanelVisibility.publicAccess(), ShowCondition.ALWAYS);
  }
}
