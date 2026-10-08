package art.arcane.gloss.persistence;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class TransactionPreparation {
    private final Limits limits;
    private final LongSupplier clock;
    private final long started;
    private long bytes;

    public TransactionPreparation(Limits limits) {
        this(limits, System::nanoTime);
    }

    TransactionPreparation(Limits limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.started = clock.getAsLong();
    }

    public void check() throws IOException {
        if (TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started) >= limits.maxMillis()) {
            throw new IOException("Transaction preparation exceeds the configured time limit");
        }
    }

    void requireCapacity(long amount) throws IOException {
        check();
        if (amount < 0 || amount > limits.maxBytes() - bytes) {
            throw new IOException("Transaction preparation exceeds the configured byte limit");
        }
    }

    void reserve(long amount) throws IOException {
        requireCapacity(amount);
        bytes += amount;
    }

    public record Limits(long maxBytes, long maxMillis) {
        public Limits {
            if (maxBytes < 1 || maxMillis < 1) {
                throw new IllegalArgumentException("Transaction preparation limits must be positive");
            }
        }
    }
}
