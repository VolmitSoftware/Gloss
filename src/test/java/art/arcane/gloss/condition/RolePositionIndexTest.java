package art.arcane.gloss.condition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePositionIndexTest {
    @Test
    void nearestFiltersBeforePopulationCapAndHandlesWorldsAndNegativeCells() {
        UUID world = new UUID(0, 1);
        UUID viewer = new UUID(0, 2);
        RolePositionIndex<String> index = new RolePositionIndex<>(List.of(
            new RolePositionIndex.Point<>(viewer, world, -64, 0, 0, "viewer"),
            new RolePositionIndex.Point<>(new UUID(0, 3), world, -63, 0, 0, "hidden"),
            new RolePositionIndex.Point<>(new UUID(0, 4), world, -66, 0, 0, "near"),
            new RolePositionIndex.Point<>(new UUID(0, 5), world, -60, 0, 0, "far"),
            new RolePositionIndex.Point<>(new UUID(0, 6), new UUID(0, 9), -64, 0, 0, "other-world")));
        assertEquals(List.of("near"), index.nearest(viewer, 3, 1,
            value -> !value.equals("hidden") && !value.equals("viewer")));
        assertTrue(index.nearest(new UUID(0, 99), 3, 1, ignored -> true).isEmpty());
    }

    @Test
    void queryVisitsOnlyNearbyPopulationAndBreaksEqualDistancesByIdentity() {
        UUID world = new UUID(0, 1);
        UUID viewer = new UUID(0, 2);
        List<RolePositionIndex.Point<String>> points = new ArrayList<>();
        points.add(new RolePositionIndex.Point<>(viewer, world, 0, 0, 0, "viewer"));
        points.add(new RolePositionIndex.Point<>(new UUID(0, 4), world, -1, 0, 0, "second"));
        points.add(new RolePositionIndex.Point<>(new UUID(0, 3), world, 1, 0, 0, "first"));
        for (int index = 10; index < 1010; index++) {
            points.add(new RolePositionIndex.Point<>(new UUID(0, index), world, index * 128, 0, 0, "distant"));
        }
        RolePositionIndex<String> spatial = new RolePositionIndex<>(points);
        AtomicInteger visibilityReads = new AtomicInteger();
        List<String> result = spatial.nearest(viewer, 64, 2, value -> {
            visibilityReads.incrementAndGet();
            return !value.equals("viewer");
        });
        assertEquals(List.of("first", "second"), result);
        assertEquals(3, visibilityReads.get());
    }
}
