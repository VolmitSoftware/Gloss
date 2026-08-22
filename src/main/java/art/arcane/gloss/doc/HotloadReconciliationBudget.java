package art.arcane.gloss.doc;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class HotloadReconciliationBudget implements AutoCloseable {
    static final int FILE_LIMIT = 32;
    static final long BYTE_LIMIT = 8L * 1024L * 1024L;
    static final long TIME_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

    private static final ThreadLocal<HotloadReconciliationBudget> ACTIVE = new ThreadLocal<>();

    private final LongSupplier clock;
    private final long startedAt;
    private int files;
    private long bytes;

    private HotloadReconciliationBudget(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.getAsLong();
    }

    static HotloadReconciliationBudget open() {
        return open(System::nanoTime);
    }

    static HotloadReconciliationBudget open(LongSupplier clock) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("hotload reconciliation budget is already active");
        }
        HotloadReconciliationBudget budget = new HotloadReconciliationBudget(clock);
        ACTIVE.set(budget);
        return budget;
    }

    static boolean tryAcquire(long fileBytes) {
        HotloadReconciliationBudget budget = ACTIVE.get();
        return budget == null || budget.acquire(fileBytes);
    }

    static long nanoTime() {
        HotloadReconciliationBudget budget = ACTIVE.get();
        return budget == null ? System.nanoTime() : budget.clock.getAsLong();
    }

    int files() {
        return files;
    }

    long bytes() {
        return bytes;
    }

    @Override
    public void close() {
        if (ACTIVE.get() == this) {
            ACTIVE.remove();
        }
    }

    private boolean acquire(long fileBytes) {
        long boundedBytes = Math.min(Math.max(0L, fileBytes), DocumentRegistry.MAX_DOCUMENT_BYTES);
        if (files >= FILE_LIMIT
            || (files > 0 && bytes + boundedBytes > BYTE_LIMIT)
            || (files > 0 && clock.getAsLong() - startedAt >= TIME_LIMIT_NANOS)) {
            return false;
        }
        files++;
        bytes += boundedBytes;
        return true;
    }
}
