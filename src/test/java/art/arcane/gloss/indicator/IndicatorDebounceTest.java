package art.arcane.gloss.indicator;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorDebounceTest {
    private static final long WINDOW_MS = 150L;

    @Test
    void theFirstSampleForAnEntityAlwaysClaims() {
        Map<UUID, Long> debounce = new ConcurrentHashMap<>();
        assertTrue(DamageIndicatorsService.claimDebounce(debounce, UUID.randomUUID(), 0L, WINDOW_MS));
    }

    @Test
    void repeatSamplesInsideTheWindowAreRejected() {
        Map<UUID, Long> debounce = new ConcurrentHashMap<>();
        UUID entity = UUID.randomUUID();

        assertTrue(DamageIndicatorsService.claimDebounce(debounce, entity, 1000L, WINDOW_MS));
        assertFalse(DamageIndicatorsService.claimDebounce(debounce, entity, 1000L, WINDOW_MS));
        assertFalse(DamageIndicatorsService.claimDebounce(debounce, entity, 1149L, WINDOW_MS));
    }

    @Test
    void theWindowReopensExactlyAtItsExpiry() {
        Map<UUID, Long> debounce = new ConcurrentHashMap<>();
        UUID entity = UUID.randomUUID();

        assertTrue(DamageIndicatorsService.claimDebounce(debounce, entity, 1000L, WINDOW_MS));
        assertTrue(DamageIndicatorsService.claimDebounce(debounce, entity, 1150L, WINDOW_MS));
        assertFalse(DamageIndicatorsService.claimDebounce(debounce, entity, 1299L, WINDOW_MS));
        assertTrue(DamageIndicatorsService.claimDebounce(debounce, entity, 1300L, WINDOW_MS));
    }

    @Test
    void separateEntitiesDebounceIndependently() {
        Map<UUID, Long> debounce = new ConcurrentHashMap<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(DamageIndicatorsService.claimDebounce(debounce, first, 0L, WINDOW_MS));
        assertTrue(DamageIndicatorsService.claimDebounce(debounce, second, 0L, WINDOW_MS));
        assertFalse(DamageIndicatorsService.claimDebounce(debounce, first, 10L, WINDOW_MS));
        assertFalse(DamageIndicatorsService.claimDebounce(debounce, second, 10L, WINDOW_MS));
        assertEquals(2, debounce.size());
    }

    @Test
    void concurrentSamplesForOneEntityYieldExactlyOneClaim() throws InterruptedException {
        Map<UUID, Long> debounce = new ConcurrentHashMap<>();
        UUID entity = UUID.randomUUID();
        int threads = 8;
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int worker = 0; worker < threads; worker++) {
            pool.execute(() -> {
                try {
                    start.await();
                    if (DamageIndicatorsService.claimDebounce(debounce, entity, 500L, WINDOW_MS)) {
                        claims.incrementAndGet();
                    }
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
        assertEquals(1, claims.get());
    }
}
