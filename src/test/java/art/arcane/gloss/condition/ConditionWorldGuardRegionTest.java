package art.arcane.gloss.condition;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A region condition resolved {@code getRegions} and {@code getId} reflectively inside the query
 * loop, per region, per evaluation. These pin the region scan and the fact that both lookups are
 * resolved once for the runtime.
 */
public class ConditionWorldGuardRegionTest {
    @Test
    void aMatchingRegionIdIsFoundCaseInsensitively() throws Exception {
        assertTrue(ConditionWorldGuard.containsRegion(new RegionSet(List.of(new Region("Spawn"))), "spawn"));
        assertTrue(ConditionWorldGuard.containsRegion(
            new RegionSet(List.of(new Region("arena"), new Region("spawn"))), "SPAWN"));
    }

    @Test
    void anAbsentRegionIdIsNotFound() throws Exception {
        assertFalse(ConditionWorldGuard.containsRegion(new RegionSet(List.of(new Region("arena"))), "spawn"));
        assertFalse(ConditionWorldGuard.containsRegion(new RegionSet(List.of()), "spawn"));
    }

    @Test
    void bothLookupsAreResolvedOnceAndReused() throws Exception {
        ConditionWorldGuard.containsRegion(new RegionSet(List.of(new Region("arena"))), "spawn");
        Method regions = cachedMethod("regionsMethod");
        Method regionId = cachedMethod("regionIdMethod");
        assertNotNull(regions);
        assertNotNull(regionId);

        ConditionWorldGuard.containsRegion(new RegionSet(List.of(new Region("spawn"))), "spawn");

        assertSame(regions, cachedMethod("regionsMethod"));
        assertSame(regionId, cachedMethod("regionIdMethod"));
    }

    @Test
    void aSubclassedRegionStillResolvesThroughTheCachedLookup() throws Exception {
        ConditionWorldGuard.containsRegion(new RegionSet(List.of(new Region("arena"))), "spawn");

        assertTrue(ConditionWorldGuard.containsRegion(new RegionSet(List.of(new NamedRegion("spawn"))), "spawn"));
    }

    private static Method cachedMethod(String name) throws Exception {
        Field field = ConditionWorldGuard.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Method) field.get(null);
    }

    public static final class RegionSet {
        private final Collection<Object> regions;

        RegionSet(List<? extends Object> regions) {
            this.regions = List.copyOf(regions);
        }

        public Collection<Object> getRegions() {
            return regions;
        }
    }

    public static class Region {
        private final String id;

        Region(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    public static final class NamedRegion extends Region {
        NamedRegion(String id) {
            super(id);
        }
    }
}
