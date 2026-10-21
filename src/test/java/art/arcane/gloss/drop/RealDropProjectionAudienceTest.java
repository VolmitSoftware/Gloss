package art.arcane.gloss.drop;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealDropProjectionAudienceTest {
    private final DropFakes.WorldFake source = new DropFakes.WorldFake(UUID.randomUUID(), 0);
    private final DropFakes.WorldFake destination = new DropFakes.WorldFake(UUID.randomUUID(), 0);

    @Test
    void nearbyPortalViewUsesItsDistanceInsteadOfTheDistantPlayer() {
        Location item = new Location(source.proxy, 104.5D, 191.0D, 4.5D);
        Location viewer = new Location(source.proxy, 8.5D, 191.0D, 13.5D);

        assertEquals(81.0D, RealDropService.audienceDistanceSquared(viewer, item, 81.0D));
    }

    @Test
    void directViewKeepsItsShorterDistance() {
        Location item = new Location(source.proxy, 4.5D, 191.0D, 4.5D);
        Location viewer = new Location(source.proxy, 4.5D, 191.0D, 7.5D);

        assertEquals(9.0D, RealDropService.audienceDistanceSquared(viewer, item, 81.0D));
        assertEquals(9.0D, RealDropService.audienceDistanceSquared(viewer, item, Double.POSITIVE_INFINITY));
    }

    @Test
    void portalViewAdmitsAnotherWorldWithoutComparingPhysicalLocations() {
        Location item = new Location(destination.proxy, 104.5D, 191.0D, 4.5D);
        Location viewer = new Location(source.proxy, 8.5D, 191.0D, 13.5D);

        assertEquals(81.0D, RealDropService.audienceDistanceSquared(viewer, item, 81.0D));
        assertEquals(0.0D, RealDropService.audienceDistanceSquared(viewer, item, 0.0D));
    }

    @Test
    void missingClosedAndInvalidViewsCannotAdmitAnotherWorld() {
        Location item = new Location(destination.proxy, 104.5D, 191.0D, 4.5D);
        Location viewer = new Location(source.proxy, 8.5D, 191.0D, 13.5D);

        for (double distance : new double[] {Double.POSITIVE_INFINITY, Double.NaN, -1.0D}) {
            assertEquals(Double.POSITIVE_INFINITY,
                RealDropService.audienceDistanceSquared(viewer, item, distance));
        }
    }
}
