package art.arcane.gloss.panel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PanelSpatialIndexTest {
  private static final UUID WORLD_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID WORLD_B = UUID.fromString("00000000-0000-0000-0000-000000000102");

  @Test
  public void replaceAllQueriesOnlyTheRequestedWorldAndRadius() {
    PanelDefinition origin = board("origin", WORLD_A, 0.0D, 0.0D);
    PanelDefinition inside = board("inside", WORLD_A, 15.9D, 0.0D);
    PanelDefinition outside = board("outside", WORLD_A, 12.0D, 12.0D);
    PanelDefinition otherWorld = board("other-world", WORLD_B, 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();

    index.replaceAll(List.of(outside, otherWorld, inside, origin));

    assertEquals(List.of(origin, inside), index.query(WORLD_A, 0.0D, 0.0D, 16.0D));
    assertEquals(List.of(otherWorld), index.query(WORLD_B, 0.0D, 0.0D, 16.0D));
    assertEquals(4, index.size());
  }

  @Test
  public void queriesCrossPositiveAndNegativeChunkBoundaries() {
    PanelDefinition positiveNear = board("positive-near", WORLD_A, 15.6D, 0.0D);
    PanelDefinition positiveAcross = board("positive-across", WORLD_A, 16.1D, 0.0D);
    PanelDefinition negativeNear = board("negative-near", WORLD_A, -15.6D, 0.0D);
    PanelDefinition negativeAcross = board("negative-across", WORLD_A, -16.1D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(positiveNear, positiveAcross, negativeNear, negativeAcross));

    assertEquals(List.of(positiveNear, positiveAcross), index.query(WORLD_A, 15.8D, 0.0D, 0.4D));
    assertEquals(List.of(negativeNear, negativeAcross), index.query(WORLD_A, -15.8D, 0.0D, 0.4D));
  }

  @Test
  public void resultsAreUniqueAndDeterministic() {
    PanelDefinition beta = board("beta", WORLD_A, -1.0D, 0.0D);
    PanelDefinition alpha = board("alpha", WORLD_A, 1.0D, 0.0D);
    PanelDefinition center = board("center", WORLD_A, 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(beta, center, alpha));

    List<PanelDefinition> result = index.query(WORLD_A, 0.0D, 0.0D, 2.0D);

    assertEquals(List.of(center, alpha, beta), result);
    assertEquals(3, result.stream().map(PanelDefinition::uuid).distinct().count());
    assertThrows(UnsupportedOperationException.class, () -> result.clear());
  }

  @Test
  public void upsertRelocatesWithoutLeavingTheOldBucketAndRemoveIsIdempotent() {
    PanelDefinition original = board("moving", WORLD_A, 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.upsert(original);

    PanelDefinition moved = original.withTransform(
        PanelTransform.at("example:world", WORLD_B, -32.1D, 0.0D, 48.1D, 0.0D));
    index.upsert(moved);

    assertTrue(index.query(WORLD_A, 0.0D, 0.0D, 1.0D).isEmpty());
    assertEquals(List.of(moved), index.query(WORLD_B, -32.1D, 48.1D, 0.0D));
    assertTrue(index.remove(original.uuid()));
    assertFalse(index.remove(original.uuid()));
    assertEquals(0, index.size());
  }

  @Test
  public void hugeFiniteRadiiScanExistingBucketsWithoutOverflow() {
    PanelDefinition distant = board("distant", WORLD_A, -32.1D, 48.1D);
    PanelDefinition origin = board("origin", WORLD_A, 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(distant, origin));

    assertEquals(List.of(origin, distant), index.query(WORLD_A, 0.0D, 0.0D, Double.MAX_VALUE));
  }

  @Test
  public void extremeFiniteCoordinatesDoNotOverflowChunkIteration() {
    PanelDefinition positive = board("positive", WORLD_A, Double.MAX_VALUE, Double.MAX_VALUE);
    PanelDefinition negative = board("negative", WORLD_A, -Double.MAX_VALUE, -Double.MAX_VALUE);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(positive, negative));

    assertEquals(List.of(positive), index.query(WORLD_A, Double.MAX_VALUE, Double.MAX_VALUE, 0.0D));
    assertEquals(List.of(negative), index.query(WORLD_A, -Double.MAX_VALUE, -Double.MAX_VALUE, 0.0D));
  }

  @Test
  public void invalidQueriesAndDuplicateIdentitySetsAreRejectedAtomically() {
    PanelDefinition original = board("original", WORLD_A, 0.0D, 0.0D);
    PanelDefinition duplicateId = new PanelDefinition(original.schemaVersion(), original.id(), UUID.randomUUID(),
        original.revision(), original.rootMenuId(), original.transform(), original.follow(), original.visibility());
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(original));

    assertThrows(NullPointerException.class, () -> index.query(null, 0.0D, 0.0D, 1.0D));
    assertThrows(IllegalArgumentException.class, () -> index.query(WORLD_A, Double.NaN, 0.0D, 1.0D));
    assertThrows(IllegalArgumentException.class, () -> index.query(WORLD_A, 0.0D, Double.POSITIVE_INFINITY, 1.0D));
    assertThrows(IllegalArgumentException.class, () -> index.query(WORLD_A, 0.0D, 0.0D, -0.1D));
    assertThrows(IllegalArgumentException.class, () -> index.query(WORLD_A, 0.0D, 0.0D, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> index.replaceAll(List.of(original, original)));
    assertThrows(IllegalArgumentException.class, () -> index.replaceAll(List.of(original, duplicateId)));
    assertThrows(IllegalArgumentException.class, () -> index.upsert(duplicateId));
    assertEquals(List.of(original), index.query(WORLD_A, 0.0D, 0.0D, 0.0D));
  }

  private static PanelDefinition board(String id, UUID worldUuid, double x, double z) {
    return new PanelDefinition(PanelDefinition.CURRENT_SCHEMA_VERSION, id,
        UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
        PanelDefinition.INITIAL_REVISION, "menu", PanelTransform.at("example:world", worldUuid, x, 64.0D, z, 0.0D),
        PanelFollow.none(), PanelVisibility.publicAccess());
  }
}
