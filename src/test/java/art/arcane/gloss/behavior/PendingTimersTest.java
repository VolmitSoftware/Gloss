package art.arcane.gloss.behavior;

import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PendingTimersTest {
    private final PendingTimers timers = new PendingTimers();
    private final UUID player = UUID.randomUUID();

    @Test
    void repeatedReleaseCannotFreeAnotherTimer() {
        PendingTimers.Lease first = timers.acquire(player, 2);
        PendingTimers.Lease second = timers.acquire(player, 2);
        assertNotNull(first);
        assertNotNull(second);
        first.close();
        first.close();
        assertEquals(1, timers.pending(player));
        assertNull(timers.acquire(player, 1));
        second.close();
        assertEquals(0, timers.pending(player));
    }

    @Test
    void oldLeasesCannotReleaseNewReservationsAfterForgetOrClear() {
        PendingTimers.Lease forgotten = timers.acquire(player, 1);
        timers.forget(player);
        PendingTimers.Lease cleared = timers.acquire(player, 1);
        timers.clear();
        PendingTimers.Lease current = timers.acquire(player, 1);
        assertNotNull(forgotten);
        assertNotNull(cleared);
        assertNotNull(current);
        forgotten.close();
        cleared.close();
        assertEquals(1, timers.pending(player));
        assertNull(timers.acquire(player, 1));
        current.close();
        assertEquals(0, timers.pending(player));
    }

    @Test
    void eachAcquireUsesTheCurrentCapAndZeroRemainsUnlimited() {
        PendingTimers.Lease first = timers.acquire(player, 1);
        assertNotNull(first);
        assertNull(timers.acquire(player, 1));
        PendingTimers.Lease second = timers.acquire(player, 2);
        PendingTimers.Lease unlimited = timers.acquire(player, 0);
        assertNotNull(second);
        assertNotNull(unlimited);
        assertEquals(3, timers.pending(player));
        assertNull(timers.acquire(player, 2));
        first.close();
        second.close();
        unlimited.close();
        assertEquals(0, timers.pending(player));
    }

    @Test
    void concurrentAcquireAndReleaseCannotDetachAnActiveCounter() throws Exception {
        AtomicInteger active = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                workers.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int iteration = 0; iteration < 1000; iteration++) {
                        PendingTimers.Lease lease = timers.acquire(player, 3);
                        if (lease == null) {
                            Thread.yield();
                            continue;
                        }
                        try {
                            assertTrue(active.incrementAndGet() <= 3);
                            Thread.yield();
                        } finally {
                            active.decrementAndGet();
                            lease.close();
                            lease.close();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, active.get());
        assertEquals(0, timers.pending(player));
        try (PendingTimers.Lease lease = timers.acquire(player, 1)) {
            assertNotNull(lease);
            assertNull(timers.acquire(player, 1));
        }
    }

    @Test
    void playerAndPlayerlessAdmissionsShareTheGlobalBudget() {
        PendingTimers.Limits limits = new PendingTimers.Limits(2, 3, 1);
        PendingTimers.Lease first = timers.acquire(player, limits);
        PendingTimers.Lease playerless = timers.acquire(null, limits);
        assertNotNull(first);
        assertNotNull(playerless);
        assertNull(timers.acquire(null, limits));
        PendingTimers.Lease second = timers.acquire(player, limits);
        assertNotNull(second);
        assertNull(timers.acquire(UUID.randomUUID(), limits));
        assertEquals(3, timers.total());
        assertEquals(1, timers.pending(null));
        playerless.close();
        playerless.close();
        assertEquals(2, timers.total());
        try (PendingTimers.Lease replacement = timers.acquire(null, limits)) {
            assertNotNull(replacement);
            assertNull(timers.acquire(player, limits));
        }
        first.close();
        second.close();
        assertEquals(0, timers.total());
    }

    @Test
    void aLoweredGlobalLimitRefusesNewTimersWithoutInvalidatingAcceptedOnes() {
        PendingTimers.Limits original = new PendingTimers.Limits(2, 3, 2);
        PendingTimers.Limits lowered = new PendingTimers.Limits(2, 1, 1);
        PendingTimers.Lease viewer = timers.acquire(player, original);
        PendingTimers.Lease playerless = timers.acquire(null, original);
        assertNotNull(viewer);
        assertNotNull(playerless);

        assertNull(timers.acquire(UUID.randomUUID(), lowered));
        assertTrue(viewer.resume());
        assertNull(timers.acquire(player, lowered));
        assertTrue(playerless.resume());
        assertFalse(playerless.resume());
        try (PendingTimers.Lease admitted = timers.acquire(null, lowered)) {
            assertNotNull(admitted);
        }
        assertEquals(0, timers.total());
    }

    @Test
    void disconnectKeepsQueuedWorkChargedUntilOldCallbacksDrain() {
        PendingTimers.Limits limits = new PendingTimers.Limits(2, 4, 1);
        PendingTimers.Lease old = timers.acquire(player, limits);
        PendingTimers.Lease oldSecond = timers.acquire(player, limits);
        PendingTimers.Lease playerless = timers.acquire(null, limits);
        timers.forget(player);
        assertEquals(3, timers.total());
        PendingTimers.Lease replacement = timers.acquire(player, limits);
        assertNotNull(replacement);

        assertFalse(old.resume());
        oldSecond.close();
        timers.forget(UUID.randomUUID());

        assertEquals(2, timers.total());
        assertEquals(1, timers.pending(player));
        assertTrue(replacement.resume());
        assertTrue(playerless.resume());
        assertEquals(0, timers.total());
    }

    @Test
    void clearingInvalidatesPlayerlessCallbacksWithoutReleasingTheirReplacements() {
        PendingTimers.Limits limits = new PendingTimers.Limits(1, 1, 1);
        PendingTimers.Lease old = timers.acquire(null, limits);
        timers.clear();
        assertEquals(1, timers.total());
        assertNull(timers.acquire(null, limits));
        assertFalse(old.resume());
        PendingTimers.Lease replacement = timers.acquire(null, limits);

        assertFalse(old.resume());
        old.close();
        assertEquals(1, timers.total());
        assertNull(timers.acquire(player, limits));
        assertTrue(replacement.resume());
        assertEquals(0, timers.total());
    }

    @Test
    void repeatedReconnectAndClearCannotBypassTheQueuedWorkCaps() {
        PendingTimers.Limits limits = new PendingTimers.Limits(1, 3, 2);
        PendingTimers.Lease oldPlayer = timers.acquire(player, limits);
        PendingTimers.Lease oldGlobal = timers.acquire(null, limits);
        timers.forget(player);
        timers.clear();
        PendingTimers.Lease replacement = timers.acquire(player, limits);
        assertNotNull(replacement);
        for (int attempt = 0; attempt < 100; attempt++) {
            timers.forget(player);
            timers.clear();
            assertNull(timers.acquire(player, limits));
            assertNull(timers.acquire(null, limits));
            assertEquals(3, timers.total());
        }
        assertFalse(oldPlayer.resume());
        assertFalse(oldGlobal.resume());
        assertFalse(replacement.resume());
        assertEquals(0, timers.total());
        try (PendingTimers.Lease admitted = timers.acquire(player, limits)) {
            assertNotNull(admitted);
        }
    }

    @Test
    void aCompletedSchedulerShutdownCannotLetAnOldLeaseDebitTheNewAccounting() {
        PendingTimers.Limits limits = new PendingTimers.Limits(1, 1, 1);
        PendingTimers.Lease old = timers.acquire(null, limits);
        timers.resetAfterShutdown();
        PendingTimers.Lease replacement = timers.acquire(null, limits);
        assertFalse(old.resume());
        assertEquals(1, timers.total());
        assertTrue(replacement.resume());
        assertEquals(0, timers.total());
    }

    @Test
    void forgettingCancelsTheNativeTaskBeforeReleasingItsQueueReservation() {
        PendingTimers.Limits limits = new PendingTimers.Limits(1, 1, 1);
        PendingTimers.Lease lease = timers.acquire(player, limits);
        AtomicInteger cancelled = new AtomicInteger();
        lease.attach(new TestTask(() -> {
            assertFalse(Thread.holdsLock(timers));
            assertEquals(1, timers.total());
            cancelled.incrementAndGet();
            lease.close();
        }));

        timers.forget(player);
        timers.forget(player);

        assertEquals(1, cancelled.get());
        assertEquals(0, timers.total());
        try (PendingTimers.Lease replacement = timers.acquire(player, limits)) {
            assertNotNull(replacement);
            assertFalse(lease.resume());
            assertEquals(1, timers.total());
        }
    }

    @Test
    void clearBeforeTaskAttachmentKeepsCapacityUntilNativeCancellationFinishes() {
        PendingTimers.Limits limits = new PendingTimers.Limits(1, 1, 1);
        PendingTimers.Lease lease = timers.acquire(null, limits);
        timers.clear();
        assertNull(timers.acquire(null, limits));
        AtomicInteger cancelled = new AtomicInteger();

        lease.attach(new TestTask(() -> {
            assertFalse(Thread.holdsLock(timers));
            assertEquals(1, timers.total());
            cancelled.incrementAndGet();
            lease.close();
        }));

        assertEquals(1, cancelled.get());
        assertEquals(0, timers.total());
        try (PendingTimers.Lease replacement = timers.acquire(null, limits)) {
            assertNotNull(replacement);
            assertFalse(lease.resume());
            assertEquals(1, timers.total());
        }
    }

    @Test
    void failedNativeCancellationRetainsCapacityUntilTheStaleCallbackDrains() {
        PendingTimers.Limits limits = new PendingTimers.Limits(1, 1, 1);
        PendingTimers.Lease lease = timers.acquire(null, limits);
        lease.attach(new TestTask(() -> { throw new IllegalStateException("native cancellation failed"); }));

        timers.clear();

        assertEquals(1, timers.total());
        assertNull(timers.acquire(null, limits));
        assertFalse(lease.resume());
        assertEquals(0, timers.total());
    }

    @Test
    void concurrentMixedAdmissionsRespectGlobalAndSubjectLimits() throws Exception {
        PendingTimers.Limits limits = new PendingTimers.Limits(2, 5, 1);
        AtomicInteger active = new AtomicInteger();
        List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<AtomicInteger> subjectCounts = List.of(new AtomicInteger(), new AtomicInteger(),
            new AtomicInteger(), new AtomicInteger());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                int subject = worker % 4;
                UUID viewer = subject == 0 ? null : players.get(subject - 1);
                workers.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int iteration = 0; iteration < 1000; iteration++) {
                        PendingTimers.Lease lease = timers.acquire(viewer, limits);
                        if (lease == null) {
                            Thread.yield();
                            continue;
                        }
                        try {
                            assertTrue(active.incrementAndGet() <= 5);
                            assertTrue(subjectCounts.get(subject).incrementAndGet() <= (subject == 0 ? 1 : 2));
                            Thread.yield();
                        } finally {
                            subjectCounts.get(subject).decrementAndGet();
                            active.decrementAndGet();
                            lease.close();
                            lease.close();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, active.get());
        assertEquals(0, timers.total());
        assertEquals(0, timers.pending(null));
        for (UUID viewer : players) {
            assertEquals(0, timers.pending(viewer));
        }
    }

    private static final class TestTask implements SchedulerUtils.TaskHandle {
        private final Runnable cancellation;
        private boolean cancelled;

        private TestTask(Runnable cancellation) {
            this.cancellation = cancellation;
        }

        @Override
        public void cancel() {
            cancellation.run();
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
