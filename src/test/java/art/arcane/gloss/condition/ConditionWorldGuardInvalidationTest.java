package art.arcane.gloss.condition;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The region adapter caches WorldGuard's query object and a retry window. A WorldGuard reload hands
 * out a new classloader, so both have to be dropped or every region condition answers false until
 * the server restarts.
 */
class ConditionWorldGuardInvalidationTest {
    @Test
    void invalidateDropsTheCachedAdapterAndRetryWindow() throws Exception {
        Field adapter = ConditionWorldGuard.class.getDeclaredField("adapter");
        adapter.setAccessible(true);
        Field retry = ConditionWorldGuard.class.getDeclaredField("retryAfterMs");
        retry.setAccessible(true);
        retry.set(null, Long.MAX_VALUE);

        ConditionWorldGuard.invalidate();

        assertNull(adapter.get(null));
        assertEquals(0L, retry.getLong(null));
    }

    @Test
    void invalidateAlsoDropsTheCachedRegionMethods() throws Exception {
        Field regions = ConditionWorldGuard.class.getDeclaredField("regionsMethod");
        regions.setAccessible(true);
        Field regionId = ConditionWorldGuard.class.getDeclaredField("regionIdMethod");
        regionId.setAccessible(true);
        regions.set(null, String.class.getMethod("trim"));
        regionId.set(null, String.class.getMethod("trim"));

        ConditionWorldGuard.invalidate();

        assertNull(regions.get(null));
        assertNull(regionId.get(null));
    }
}
