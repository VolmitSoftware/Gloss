package art.arcane.gloss.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionPreparationTest {
    @Test
    void byteAdmissionCountsTheWholePreparationWithoutOverflow() throws IOException {
        TransactionPreparation preparation = new TransactionPreparation(
            new TransactionPreparation.Limits(Long.MAX_VALUE, 1000), () -> 0L);
        preparation.reserve(Long.MAX_VALUE - 1);
        assertThrows(IOException.class, () -> preparation.reserve(2));
        preparation.reserve(1);
        assertThrows(IOException.class, () -> preparation.reserve(1));
        assertThrows(IOException.class, () -> preparation.reserve(-1));
    }

    @Test
    void deadlineUsesElapsedMonotonicTimeAndRejectsTheBoundary() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 500000);
        TransactionPreparation preparation = new TransactionPreparation(
            new TransactionPreparation.Limits(1024, 1), clock::get);
        clock.addAndGet(999999);
        assertDoesNotThrow(preparation::check);
        clock.incrementAndGet();
        assertThrows(IOException.class, preparation::check);
    }
}
