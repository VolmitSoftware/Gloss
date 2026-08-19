package art.arcane.gloss.group;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupRefreshTest {

    @Test
    void aFreshEntryIsServedWithoutConsultingTheResolver() {
        Map<UUID, GroupService.CachedPrimaryGroup> cache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        cache.put(uuid, new GroupService.CachedPrimaryGroup("vip", 1000L));
        AtomicInteger calls = new AtomicInteger();

        GroupService.CachedPrimaryGroup served = GroupService.refresh(cache, uuid, 1001L, key -> {
            calls.incrementAndGet();
            return "staff";
        });

        assertEquals("vip", served.name());
        assertEquals(0, calls.get());
    }

    @Test
    void aStaleEntryIsResolvedOnceAndReplaced() {
        Map<UUID, GroupService.CachedPrimaryGroup> cache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        cache.put(uuid, new GroupService.CachedPrimaryGroup("vip", 0L));
        AtomicInteger calls = new AtomicInteger();

        GroupService.CachedPrimaryGroup served = GroupService.refresh(cache,
            uuid, GroupService.PRIMARY_GROUP_TTL_MS, key -> {
                calls.incrementAndGet();
                return "  STAFF ";
            });

        assertEquals("staff", served.name());
        assertEquals(1, calls.get());
        assertEquals(served, cache.get(uuid));
    }

    @Test
    void resolvedNullsAreCachedSoTheResolverIsNotHitAgainWithinTheTtl() {
        Map<UUID, GroupService.CachedPrimaryGroup> cache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        Function<UUID, String> resolver = key -> {
            calls.incrementAndGet();
            return "   ";
        };

        assertNull(GroupService.refresh(cache, uuid, 0L, resolver).name());
        assertNull(GroupService.refresh(cache, uuid, 1L, resolver).name());
        assertEquals(1, calls.get());
    }

    @Test
    void concurrentRefreshesForOneKeyCollapseToASingleResolution() throws InterruptedException {
        Map<UUID, GroupService.CachedPrimaryGroup> cache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int worker = 0; worker < threads; worker++) {
            pool.execute(() -> {
                try {
                    start.await();
                    GroupService.refresh(cache, uuid, 5_000L, key -> {
                        calls.incrementAndGet();
                        return "vip";
                    });
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10L, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, calls.get());
        assertEquals("vip", cache.get(uuid).name());
    }

    @Test
    void jitterIsDeterministicPerKeyAndStaysInsideItsBound() {
        for (int sample = 0; sample < 512; sample++) {
            UUID uuid = UUID.randomUUID();
            long jitter = GroupService.jitterMs(uuid);
            assertTrue(jitter >= 0L);
            assertTrue(jitter < GroupService.PRIMARY_GROUP_TTL_JITTER_MS);
            assertEquals(jitter, GroupService.jitterMs(uuid));
        }
    }

    @Test
    void jitterStaggersExpiryWithoutEverShorteningTheTtl() {
        Map<UUID, GroupService.CachedPrimaryGroup> cache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        long now = 10_000L;

        GroupService.CachedPrimaryGroup entry = GroupService.refresh(cache, uuid, now, key -> "vip");

        assertTrue(entry.fresh(now));
        assertTrue(entry.fresh(now + GroupService.PRIMARY_GROUP_TTL_MS - 1L));
        assertFalse(entry.fresh(now + GroupService.PRIMARY_GROUP_TTL_MS
            + GroupService.PRIMARY_GROUP_TTL_JITTER_MS));
    }

    @Test
    void distinctKeysDoNotShareCacheEntries() {
        Map<UUID, GroupService.CachedPrimaryGroup> cache = new ConcurrentHashMap<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        GroupService.refresh(cache, first, 0L, key -> "vip");
        GroupService.refresh(cache, second, 0L, key -> "staff");

        assertEquals("vip", cache.get(first).name());
        assertEquals("staff", cache.get(second).name());
        assertEquals(2, cache.size());
    }
}
