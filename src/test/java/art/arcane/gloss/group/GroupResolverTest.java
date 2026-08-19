package art.arcane.gloss.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupResolverTest {
    @Test
    void groupNamesNormalizeToLowercase() {
        assertEquals("staff", GroupService.normalizeGroupName("Staff"));
        assertEquals("staff", GroupService.normalizeGroupName(" STAFF "));
        assertEquals("builders", GroupService.normalizeGroupName("builders"));
    }

    @Test
    void blankAndNullGroupNamesResolveToNull() {
        assertNull(GroupService.normalizeGroupName(null));
        assertNull(GroupService.normalizeGroupName(""));
        assertNull(GroupService.normalizeGroupName("   "));
    }

    @Test
    void cachedGroupsStayFreshInsideTheTtl() {
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup("staff", 1000L);

        assertTrue(cached.fresh(1000L));
        assertTrue(cached.fresh(1000L + GroupService.PRIMARY_GROUP_TTL_MS - 1L));
        assertFalse(cached.fresh(1000L + GroupService.PRIMARY_GROUP_TTL_MS));
    }

    @Test
    void cacheEntriesCarryNullResolutions() {
        GroupService.CachedPrimaryGroup cached = new GroupService.CachedPrimaryGroup(null, 0L);

        assertNull(cached.name());
        assertTrue(cached.fresh(GroupService.PRIMARY_GROUP_TTL_MS - 1L));
    }
}
