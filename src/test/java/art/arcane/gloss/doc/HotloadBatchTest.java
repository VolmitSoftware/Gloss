package art.arcane.gloss.doc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotloadBatchTest {
    @Test
    void recordsCoalesceByKindAndDrainInStableOrder() {
        HotloadBatch batch = new HotloadBatch();
        batch.record("menus", 2);
        batch.record("boards", 1);
        batch.record("menus", 3);

        HotloadBatch.Snapshot snapshot = batch.drain();

        assertEquals(List.of("boards", "menus"), List.copyOf(snapshot.changesByKind().keySet()));
        assertEquals(1, snapshot.changesByKind().get("boards"));
        assertEquals(5, snapshot.changesByKind().get("menus"));
        assertEquals(6, snapshot.totalChanges());
        assertTrue(batch.drain().isEmpty());
    }

    @Test
    void invalidRecordsAreIgnored() {
        HotloadBatch batch = new HotloadBatch();
        batch.record(null, 1);
        batch.record("", 1);
        batch.record("boards", 0);
        batch.record("boards", -1);

        assertTrue(batch.drain().isEmpty());
    }
}
