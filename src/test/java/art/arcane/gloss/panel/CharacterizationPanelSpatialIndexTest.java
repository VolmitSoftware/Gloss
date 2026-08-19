package art.arcane.gloss.panel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Characterization pins for {@link PanelSpatialIndex} query semantics (plan item T15/D6): the
 * exact result lists — membership, dedup, and (distance, id, uuid) ordering — for a fixed set of
 * placements, across both candidate-collection paths and across incremental maintenance. A
 * re-publication of the index as an immutable snapshot must reproduce every one of these lists
 * verbatim; {@code PanelSpatialIndexTest} keeps the narrower per-rule pins.
 */
public class CharacterizationPanelSpatialIndexTest {
  private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-000000000302");

  @Test
  public void theQueryTableIsPinnedAcrossAFixedPlacementSet() {
    PanelDefinition origin = board("origin", OVERWORLD, 0.0D, 0.0D);
    PanelDefinition east = board("east", OVERWORLD, 20.0D, 0.0D);
    PanelDefinition west = board("west", OVERWORLD, -20.0D, 0.0D);
    PanelDefinition north = board("north", OVERWORLD, 0.0D, -20.0D);
    PanelDefinition far = board("far", OVERWORLD, 300.0D, 300.0D);
    PanelDefinition sameSpotA = board("a-same-spot", OVERWORLD, 8.0D, 6.0D);
    PanelDefinition sameSpotB = board("b-same-spot", OVERWORLD, 8.0D, 6.0D);
    PanelDefinition netherOrigin = board("nether-origin", NETHER, 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(far, west, sameSpotB, north, east, netherOrigin, sameSpotA, origin));

    // Distance ordering, then id: the two co-located boards tie on distance and order by id.
    assertEquals(List.of(origin, sameSpotA, sameSpotB, east, north, west),
        index.query(OVERWORLD, 0.0D, 0.0D, 25.0D));
    // Membership boundary is inclusive: at exactly radius 20 the ring boards are still matched.
    assertEquals(List.of(origin, sameSpotA, sameSpotB, east, north, west),
        index.query(OVERWORLD, 0.0D, 0.0D, 20.0D));
    // Just under the ring distance they drop out together.
    assertEquals(List.of(origin, sameSpotA, sameSpotB),
        index.query(OVERWORLD, 0.0D, 0.0D, 19.999D));
    // A query centered elsewhere reorders by the new distances.
    assertEquals(List.of(east, sameSpotA, sameSpotB, origin, north),
        index.query(OVERWORLD, 20.0D, 0.0D, 30.0D));
    // Worlds never bleed into each other.
    assertEquals(List.of(netherOrigin), index.query(NETHER, 0.0D, 0.0D, 25.0D));
    // A tiny-radius query (window path) and a world-sized query (bucket-scan path) agree on
    // membership for the same center.
    assertEquals(List.of(origin), index.query(OVERWORLD, 0.0D, 0.0D, 1.0D));
    List<PanelDefinition> everything = index.query(OVERWORLD, 0.0D, 0.0D, 1.0E9D);
    assertEquals(List.of(origin, sameSpotA, sameSpotB, east, north, west, far), everything);
  }

  @Test
  public void incrementalMaintenanceMatchesABulkRebuild() {
    List<PanelDefinition> placements = grid();
    PanelSpatialIndex incremental = new PanelSpatialIndex();
    for (PanelDefinition board : placements) {
      incremental.upsert(board);
    }
    // Move one board, delete another, then re-add the deleted one — the mutation shapes the
    // runtime uses for follow-boards and reload edges.
    PanelDefinition moved = placements.get(3).withTransform(
        PanelTransform.at("example:world", OVERWORLD, -48.5D, 64.0D, 17.0D, 0.0D));
    incremental.upsert(moved);
    incremental.remove(placements.get(7).uuid());
    incremental.upsert(placements.get(7));

    List<PanelDefinition> finalState = new ArrayList<>(placements);
    finalState.set(3, moved);
    PanelSpatialIndex bulk = new PanelSpatialIndex();
    bulk.replaceAll(finalState);

    double[][] probes = {
        {0.0D, 0.0D, 24.0D},
        {-40.0D, 16.0D, 33.0D},
        {17.0D, -17.0D, 0.5D},
        {5.0D, 5.0D, 1.0E7D},
    };
    for (double[] probe : probes) {
      assertEquals("incremental and bulk indexes must answer identically at ("
              + probe[0] + ", " + probe[1] + ") r=" + probe[2],
          bulk.query(OVERWORLD, probe[0], probe[1], probe[2]),
          incremental.query(OVERWORLD, probe[0], probe[1], probe[2]));
    }
    assertEquals(bulk.size(), incremental.size());
    assertEquals(bulk.list(), incremental.list());
  }

  @Test
  public void aMovedBoardIsQueryableOnlyAtItsNewPlacement() {
    PanelDefinition original = board("mover", OVERWORLD, 4.0D, 4.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.upsert(original);

    PanelDefinition moved = original.withTransform(
        PanelTransform.at("example:world", OVERWORLD, 100.0D, 64.0D, 100.0D, 0.0D));
    index.upsert(moved);

    assertTrue(index.query(OVERWORLD, 4.0D, 4.0D, 10.0D).isEmpty());
    assertEquals(List.of(moved), index.query(OVERWORLD, 100.0D, 100.0D, 0.0D));
    assertEquals(1, index.size());
  }

  private static List<PanelDefinition> grid() {
    List<PanelDefinition> boards = new ArrayList<>();
    int i = 0;
    for (int x = -2; x <= 2; x++) {
      for (int z = -1; z <= 1; z++) {
        boards.add(board("grid-" + (i++), OVERWORLD, x * 17.0D, z * 17.0D));
      }
    }
    return boards;
  }

  private static PanelDefinition board(String id, UUID worldUuid, double x, double z) {
    return new PanelDefinition(PanelDefinition.CURRENT_SCHEMA_VERSION, id,
        UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
        PanelDefinition.INITIAL_REVISION, "menu",
        PanelTransform.at("example:world", worldUuid, x, 64.0D, z, 0.0D),
        PanelFollow.none(), PanelVisibility.publicAccess());
  }
}
