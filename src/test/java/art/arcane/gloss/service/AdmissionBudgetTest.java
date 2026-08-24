package art.arcane.gloss.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdmissionBudgetTest {
    @Test
    void fixedCeilingRejectsExcessAndReopensAfterRelease() {
        AdmissionBudget budget = new AdmissionBudget(2);
        AdmissionBudget.Lease first = budget.tryAcquire();
        AdmissionBudget.Lease second = budget.tryAcquire();

        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryAcquire());
        assertEquals(2, budget.active());

        first.close();
        assertNotNull(budget.tryAcquire());
    }

    @Test
    void leaseReleasesCapacityExactlyOnce() {
        AdmissionBudget budget = new AdmissionBudget(1);
        AdmissionBudget.Lease lease = budget.tryAcquire();

        assertNotNull(lease);
        lease.close();
        lease.close();

        assertEquals(0, budget.active());
        assertNotNull(budget.tryAcquire());
    }

    @Test
    void requestedCeilingCanTemporarilyNarrowTheHardLimit() {
        AdmissionBudget budget = new AdmissionBudget(8);
        AdmissionBudget.Lease first = budget.tryAcquire(2);
        AdmissionBudget.Lease second = budget.tryAcquire(2);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryAcquire(2));
        assertNotNull(budget.tryAcquire(8));
        assertEquals(3, budget.active());
    }

    @Test
    void concurrentAdmissionsNeverExceedTheCeiling() throws Exception {
        int ceiling = 32;
        int attempts = 256;
        AdmissionBudget budget = new AdmissionBudget(ceiling);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<AdmissionBudget.Lease>> futures = new ArrayList<>(attempts);
        try {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return budget.tryAcquire();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<AdmissionBudget.Lease> future : futures) {
                if (future.get() != null) {
                    accepted++;
                }
            }
            assertEquals(ceiling, accepted);
            assertEquals(ceiling, budget.active());
        } finally {
            executor.shutdownNow();
        }
    }
}
