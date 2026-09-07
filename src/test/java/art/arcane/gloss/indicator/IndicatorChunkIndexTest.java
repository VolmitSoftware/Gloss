package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorChunkIndexTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    @Test
    void aViewerVisitsOnlyTheIndicatorsInsideTheScanRadius() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.add(WORLD, 0, 0, 4, "same-chunk");
        index.add(WORLD, 3, -2, 4, "edge-chunk");
        index.add(WORLD, 40, 0, 4, "far-chunk");

        assertEquals(List.of("edge-chunk", "same-chunk"), visited(index, WORLD, 0, 0));
    }

    @Test
    void indicatorsInAnotherWorldAreNeverVisited() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.add(WORLD, 0, 0, 4, "here");
        index.add(OTHER_WORLD, 0, 0, 4, "elsewhere");

        assertEquals(List.of("here"), visited(index, WORLD, 0, 0));
        assertEquals(List.of("elsewhere"), visited(index, OTHER_WORLD, 0, 0));
    }

    @Test
    void removalDropsTheIndicatorAndEmptiesTheIndex() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.add(WORLD, 5, 5, 4, "expiring");
        assertFalse(index.isEmpty());

        index.remove(WORLD, 5, 5, "expiring");

        assertTrue(index.isEmpty());
        assertEquals(List.of(), visited(index, WORLD, 5, 5));
    }

    @Test
    void removingAnUnknownIndicatorIsASafeNoOp() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.remove(WORLD, 1, 1, "never-added");
        index.add(WORLD, 1, 1, 4, "kept");
        index.remove(WORLD, 1, 1, "never-added");
        index.remove(null, 1, 1, "kept");

        assertEquals(List.of("kept"), visited(index, WORLD, 1, 1));
        assertFalse(index.isEmpty());
    }

    @Test
    void addingTheSameIndicatorTwiceKeepsOneEntry() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.add(WORLD, 1, 1, 4, "once");
        index.add(WORLD, 1, 1, 4, "once");

        assertEquals(List.of("once"), visited(index, WORLD, 1, 1));

        index.remove(WORLD, 1, 1, "once");

        assertTrue(index.isEmpty());
    }

    @Test
    void theScanRadiusCoversEveryChunkTheViewRangeCanReach() {
        assertEquals(1, IndicatorChunkIndex.chunkRadius(0.0D));
        assertEquals(1, IndicatorChunkIndex.chunkRadius(4.0D));
        assertEquals(4, IndicatorChunkIndex.chunkRadius(48.0D));
        assertEquals(9, IndicatorChunkIndex.chunkRadius(128.0D));
    }

    @Test
    void noViewerInsideTheRangeOfAnAnchorFallsOutsideTheScannedChunks() {
        double range = 48.0D;
        int radius = IndicatorChunkIndex.chunkRadius(range);
        for (int anchorX = 0; anchorX < 16; anchorX++) {
            for (int offset = -160; offset <= 160; offset++) {
                double anchor = anchorX + 0.5D;
                double viewer = anchor + offset;
                if (Math.abs(viewer - anchor) > range) {
                    continue;
                }
                int anchorChunk = (int) Math.floor(anchor) >> 4;
                int viewerChunk = (int) Math.floor(viewer) >> 4;
                assertTrue(Math.abs(anchorChunk - viewerChunk) <= radius,
                    "viewer at " + viewer + " must be scanned for an anchor at " + anchor);
            }
        }
    }

    @Test
    void theScanRadiusFollowsTheWidestLiveEntry() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.add(WORLD, 0, 0, 1, "narrow");
        index.add(WORLD, 6, 0, 8, "wide");

        assertEquals(List.of("narrow", "wide"), visited(index, WORLD, 0, 0));
    }

    @Test
    void clearDropsEveryWorld() {
        IndicatorChunkIndex<String> index = new IndicatorChunkIndex<>();
        index.add(WORLD, 0, 0, 4, "one");
        index.add(OTHER_WORLD, 9, 9, 4, "two");

        index.clear();

        assertTrue(index.isEmpty());
        assertEquals(List.of(), visited(index, WORLD, 0, 0));
        assertEquals(List.of(), visited(index, OTHER_WORLD, 9, 9));
    }

    private static List<String> visited(IndicatorChunkIndex<String> index, UUID world, int chunkX, int chunkZ) {
        List<String> seen = new ArrayList<>();
        index.forEachNear(world, chunkX, chunkZ, seen::add);
        Collections.sort(seen);
        return seen;
    }
}
