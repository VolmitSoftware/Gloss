package art.arcane.gloss.panel;

import art.arcane.gloss.condition.ShowCondition;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The index publishes an immutable snapshot per change instead of guarding a mutable one with a
 * lock: readers take no lock, a rejected write leaves the previous snapshot in place, and a
 * generation counter lets a caller reuse a query result until the index actually moves.
 * {@code CharacterizationPanelSpatialIndexTest} pins the query answers themselves.
 */
public class PanelSpatialIndexSnapshotTest {
  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000401");

  @Test
  public void theGenerationMovesOnEveryRealChangeAndOnNothingElse() {
    PanelDefinition origin = board("origin", 0.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    assertTrue(index.isEmpty());
    long empty = index.generation();

    index.upsert(origin);
    long afterInsert = index.generation();
    assertNotEquals("inserting a panel is a change", empty, afterInsert);
    assertFalse(index.isEmpty());

    index.upsert(origin);
    assertEquals("re-publishing an identical definition changes nothing", afterInsert, index.generation());

    index.upsert(origin.withTransform(PanelTransform.at("example:world", WORLD, 1.0D, 64.0D, 0.0D, 0.0D)));
    long afterMove = index.generation();
    assertNotEquals("a moved panel is a change", afterInsert, afterMove);

    assertFalse("removing an unknown panel changes nothing", index.remove(UUID.randomUUID()));
    assertEquals(afterMove, index.generation());

    assertTrue(index.remove(origin.uuid()));
    assertNotEquals("a removed panel is a change", afterMove, index.generation());
    assertTrue(index.isEmpty());
  }

  @Test
  public void aRejectedWriteLeavesThePublishedIndexUntouched() {
    PanelDefinition origin = board("origin", 0.0D, 0.0D);
    PanelDefinition idThief = new PanelDefinition(origin.schemaVersion(), origin.id(), UUID.randomUUID(),
        origin.revision(), origin.rootMenuId(), origin.transform(), origin.follow(), origin.visibility(), origin.show());
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(origin));
    long published = index.generation();

    assertThrows(IllegalArgumentException.class, () -> index.upsert(idThief));
    assertThrows(IllegalArgumentException.class, () -> index.replaceAll(List.of(origin, idThief)));

    assertEquals("a rejected write must not publish", published, index.generation());
    assertEquals(List.of(origin), index.list());
    assertEquals(List.of(origin), index.query(WORLD, 0.0D, 0.0D, 1.0D));
  }

  @Test
  public void aQueryResultIsUnaffectedByLaterWrites() {
    PanelDefinition origin = board("origin", 0.0D, 0.0D);
    PanelDefinition neighbour = board("neighbour", 4.0D, 0.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(origin, neighbour));

    List<PanelDefinition> taken = index.query(WORLD, 0.0D, 0.0D, 8.0D);
    index.remove(neighbour.uuid());
    index.upsert(board("late", 1.0D, 1.0D));

    assertEquals("a handed-out result is a snapshot, not a live view",
        List.of(origin, neighbour), taken);
    assertThrows(UnsupportedOperationException.class, taken::clear);
  }

  @Test
  public void theOrderedListingIsSortedByIdAndImmutable() {
    PanelDefinition charlie = board("charlie", 0.0D, 0.0D);
    PanelDefinition alpha = board("alpha", 100.0D, 100.0D);
    PanelDefinition bravo = board("bravo", -100.0D, -100.0D);
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.upsert(charlie);
    index.upsert(alpha);
    index.upsert(bravo);

    assertEquals(List.of(alpha, bravo, charlie), index.list());
    assertThrows(UnsupportedOperationException.class, () -> index.list().clear());
    assertEquals(3, index.size());
  }

  @Test
  public void concurrentBatchMovesNeverPublishHalfAnUpdate() throws Exception {
    PanelDefinition firstNear = board("first", 0.0D, 0.0D);
    PanelDefinition secondNear = board("second", 1.0D, 0.0D);
    PanelDefinition firstFar = firstNear.withTransform(
        PanelTransform.at("example:world", WORLD, 4096.0D, 64.0D, 4096.0D, 0.0D));
    PanelDefinition secondFar = secondNear.withTransform(
        PanelTransform.at("example:world", WORLD, 4097.0D, 64.0D, 4096.0D, 0.0D));
    PanelSpatialIndex index = new PanelSpatialIndex();
    index.replaceAll(List.of(firstNear, secondNear));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> writer = executor.submit(() -> {
        await(start);
        for (int update = 0; update < 2_000; update++) {
          index.upsertAll((update & 1) == 0
              ? List.of(firstFar, secondFar)
              : List.of(firstNear, secondNear));
        }
      });
      Future<?> reader = executor.submit(() -> {
        await(start);
        for (int query = 0; query < 4_000; query++) {
          int matches = index.query(WORLD, 0.0D, 0.0D, 8.0D).size();
          if (matches != 0 && matches != 2) {
            throw new AssertionError("observed a half-published batch with " + matches + " matches");
          }
        }
      });
      start.countDown();
      writer.get(10L, TimeUnit.SECONDS);
      reader.get(10L, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interruption);
    }
  }

  private static PanelDefinition board(String id, double x, double z) {
    return new PanelDefinition(PanelDefinition.CURRENT_SCHEMA_VERSION, id,
        UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
        PanelDefinition.INITIAL_REVISION, "menu",
        PanelTransform.at("example:world", WORLD, x, 64.0D, z, 0.0D),
        PanelFollow.none(), PanelVisibility.publicAccess(), ShowCondition.ALWAYS);
  }
}
