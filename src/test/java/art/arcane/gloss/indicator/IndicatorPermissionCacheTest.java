package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorPermissionCacheTest {
    @Test
    void unknownAndNewlyTrackedPlayersAreDenySafe() {
        IndicatorPermissionCache cache = new IndicatorPermissionCache();
        UUID playerId = UUID.randomUUID();

        assertFalse(cache.allowed(playerId));
        cache.track(playerId);
        assertFalse(cache.allowed(playerId));
    }

    @Test
    void entityOwnedRefreshesReplaceTheSnapshot() {
        IndicatorPermissionCache cache = new IndicatorPermissionCache();
        UUID playerId = UUID.randomUUID();
        cache.track(playerId);

        cache.update(playerId, true);
        assertTrue(cache.allowed(playerId));
        cache.update(playerId, false);
        assertFalse(cache.allowed(playerId));
    }

    @Test
    void refreshOrderRotatesWithoutGrowingDuplicates() {
        IndicatorPermissionCache cache = new IndicatorPermissionCache();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cache.track(first);
        cache.track(second);
        cache.track(first);

        assertEquals(first, cache.claimNextRefresh(0L, 5000L));
        assertEquals(second, cache.claimNextRefresh(0L, 5000L));
        assertNull(cache.claimNextRefresh(0L, 5000L));
        assertEquals(first, cache.claimNextRefresh(5000L, 5000L));
        assertEquals(second, cache.claimNextRefresh(5000L, 5000L));
    }

    @Test
    void removalDropsSnapshotAndRefreshWork() {
        IndicatorPermissionCache cache = new IndicatorPermissionCache();
        UUID playerId = UUID.randomUUID();
        cache.track(playerId);
        cache.update(playerId, true);

        cache.remove(playerId);

        assertFalse(cache.allowed(playerId));
        assertNull(cache.claimNextRefresh(5000L, 5000L));
        cache.update(playerId, true);
        assertFalse(cache.allowed(playerId));
    }

    @Test
    void snapshotUpdatesDoNotPostponeTheClaimedRefresh() {
        IndicatorPermissionCache cache = new IndicatorPermissionCache();
        UUID playerId = UUID.randomUUID();
        cache.track(playerId);
        assertEquals(playerId, cache.claimNextRefresh(0L, 5000L));

        for (int update = 0; update < 1000; update++) {
            cache.update(playerId, (update & 1) == 0);
        }

        assertNull(cache.claimNextRefresh(4999L, 5000L));
        assertEquals(playerId, cache.claimNextRefresh(5000L, 5000L));
    }

    @Test
    void failedSchedulingCanMakeADeferredEntryImmediatelyDue() {
        IndicatorPermissionCache cache = new IndicatorPermissionCache();
        UUID playerId = UUID.randomUUID();
        cache.track(playerId);
        cache.defer(playerId, 0L, 5000L);
        assertNull(cache.claimNextRefresh(4999L, 5000L));

        cache.makeDue(playerId);

        assertEquals(playerId, cache.claimNextRefresh(4999L, 5000L));
    }
}
