package art.arcane.gloss.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1 characterization pins for group-resolution TTL semantics: the freshness
 * window of a cached primary group, the normalization applied to every resolved
 * name, and the null-player fast path. Lane B's TTL jitter and single-flight
 * refresh (B6) must either preserve these outcomes or change an assertion with an
 * explicit report note. Frozen contracts.
 */
class CharacterizationGroupTtlTest {

    // --- freshness window boundaries ---

    @Test
    void entriesAreFreshFromCreationUpToButExcludingTheTtl() {
        long at = 0L;
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup("vip", at);

        assertTrue(cached.fresh(at));
        assertTrue(cached.fresh(at + 1L));
        assertTrue(cached.fresh(at + GroupService.PRIMARY_GROUP_TTL_MS - 1L));
        assertFalse(cached.fresh(at + GroupService.PRIMARY_GROUP_TTL_MS));
        assertFalse(cached.fresh(at + GroupService.PRIMARY_GROUP_TTL_MS + 1L));
        assertFalse(cached.fresh(Long.MAX_VALUE / 2L));
    }

    @Test
    void freshnessDependsOnlyOnElapsedTimeNotOnAbsoluteTimestamps() {
        long lateBase = 1_700_000_000_000L;
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup("staff", lateBase);

        assertTrue(cached.fresh(lateBase + GroupService.PRIMARY_GROUP_TTL_MS - 1L));
        assertFalse(cached.fresh(lateBase + GroupService.PRIMARY_GROUP_TTL_MS));
    }

    @Test
    void aClockThatRunsBackwardLeavesEntriesFresh() {
        // Current behavior with the wall-clock source: now < at yields a negative
        // elapsed time, which reads as fresh. Pinned so a clock-source change is a
        // deliberate, noted decision rather than an accident.
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup("vip", 10_000L);
        assertTrue(cached.fresh(9_000L));
        assertTrue(cached.fresh(0L));
    }

    @Test
    void nullResolutionsAreCachedWithTheSameTtlAsRealGroups() {
        // A player with no Vault group is a cached "null" for a full TTL, not re-resolved
        // on every render.
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup(null, 500L);
        assertNull(cached.name());
        assertTrue(cached.fresh(500L + GroupService.PRIMARY_GROUP_TTL_MS - 1L));
        assertFalse(cached.fresh(500L + GroupService.PRIMARY_GROUP_TTL_MS));
    }

    @Test
    void cacheEntriesRoundTripTheirNameAndTimestamp() {
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup("builders", 1234L);
        assertEquals("builders", cached.name());
        assertEquals(1234L, cached.at());
    }

    // --- name normalization feeding the cache ---

    @Test
    void normalizationTrimsEndsButKeepsInteriorWhitespace() {
        assertEquals("team red", GroupService.normalizeGroupName("  Team Red  "));
    }

    @Test
    void normalizationLowercasesWithRootLocaleSemantics() {
        assertEquals("admin", GroupService.normalizeGroupName("ADMIN"));
        assertEquals("mod-1", GroupService.normalizeGroupName("Mod-1"));
    }

    @Test
    void unresolvableNamesNormalizeToNullSoTheCacheStoresANullEntry() {
        assertNull(GroupService.normalizeGroupName(null));
        assertNull(GroupService.normalizeGroupName(""));
        assertNull(GroupService.normalizeGroupName(" \t "));
    }

    // --- null-player fast path ---

    @Test
    void nullPlayerResolvesToEmptyWithoutTouchingThePluginOrVault() {
        GroupService service = new GroupService(null);
        assertTrue(service.primaryGroupFor(null).isEmpty());
    }
}
