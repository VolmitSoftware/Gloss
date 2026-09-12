package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hotload notice is the only place an operator watching the game sees a reload land, so files
 * a poll refused for their schema travel with the batch instead of being dropped on the floor.
 */
class HotloadBatchSkippedTest {
    @Test
    void skippedCountsTravelWithTheSnapshot() {
        HotloadBatch batch = new HotloadBatch();
        batch.record("boards", 2);
        batch.recordSkipped("boards", 1);
        batch.recordSkipped("motd", 1);

        HotloadBatch.Snapshot snapshot = batch.drain();

        assertEquals(2, snapshot.totalChanges());
        assertEquals(2, snapshot.totalSkipped());
        assertEquals(1, snapshot.skippedByKind().get("motd"));
    }

    @Test
    void aBatchOfNothingButSkipsStillDelivers() {
        HotloadBatch batch = new HotloadBatch();
        batch.recordSkipped("emoji", 3);

        HotloadBatch.Snapshot snapshot = batch.drain();

        assertFalse(snapshot.isEmpty(), "skipped files are worth a notice on their own");
        assertEquals(0, snapshot.totalChanges());
        assertEquals(3, snapshot.totalSkipped());
    }

    @Test
    void drainingClearsBothLedgers() {
        HotloadBatch batch = new HotloadBatch();
        batch.record("boards", 1);
        batch.recordSkipped("boards", 1);
        batch.drain();

        assertTrue(batch.drain().isEmpty());
    }
}
