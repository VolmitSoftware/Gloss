package art.arcane.gloss.tab;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastDriverLifecycleTest {
    @Test
    void concurrentStartsCreateOnlyOneRepeatingTask() throws Exception {
        TablistService.FastDriverLifecycle lifecycle = new TablistService.FastDriverLifecycle();
        AtomicInteger identifiers = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        Set<Integer> active = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int task = 0; task < 64; task++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    lifecycle.reconcile(true, () -> {
                        starts.incrementAndGet();
                        int identifier = identifiers.incrementAndGet();
                        active.add(identifier);
                        return identifier;
                    }, active::remove);
                }));
            }
            start.countDown();
            await(futures);

            assertEquals(1, starts.get());
            assertEquals(1, active.size());
            assertTrue(lifecycle.required());
            assertTrue(lifecycle.taskId() > 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentStartAndCancellationFinishInALinearizedState() throws Exception {
        TablistService.FastDriverLifecycle lifecycle = new TablistService.FastDriverLifecycle();
        AtomicInteger identifiers = new AtomicInteger();
        Set<Integer> active = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int task = 0; task < 128; task++) {
                boolean required = (task & 1) == 0;
                futures.add(executor.submit(() -> {
                    await(start);
                    lifecycle.reconcile(required, () -> {
                        int identifier = identifiers.incrementAndGet();
                        active.add(identifier);
                        return identifier;
                    }, active::remove);
                }));
            }
            start.countDown();
            await(futures);

            assertTrue(active.size() <= 1);
            assertEquals(lifecycle.required(), lifecycle.taskId() != -1);
            assertEquals(lifecycle.required(), !active.isEmpty());
            lifecycle.stop(active::remove);
            assertFalse(lifecycle.required());
            assertEquals(-1, lifecycle.taskId());
            assertTrue(active.isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }

    private static void await(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) {
            future.get(5L, TimeUnit.SECONDS);
        }
    }
}
