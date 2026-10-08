package art.arcane.gloss.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AdmissionBudget {
    private final int maximum;
    private final AtomicInteger active = new AtomicInteger();

    public AdmissionBudget(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        this.maximum = maximum;
    }

    public Lease tryAcquire() {
        return tryAcquire(maximum);
    }

    public Lease tryAcquire(int requestedMaximum) {
        int limit = Math.min(maximum, Math.max(0, requestedMaximum));
        while (true) {
            int current = active.get();
            if (current >= limit) {
                return null;
            }
            if (active.compareAndSet(current, current + 1)) {
                return new Lease(this);
            }
        }
    }

    public int active() {
        return active.get();
    }

    private void release() {
        active.decrementAndGet();
    }

    public static final class Lease implements AutoCloseable {
        private final AdmissionBudget owner;
        private final Reservation reservation;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(AdmissionBudget owner) {
            this.owner = owner;
            this.reservation = null;
        }

        Lease(Reservation reservation) {
            this.owner = null;
            this.reservation = reservation;
        }

        Reservation reservation() {
            return released.get() ? null : reservation;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                if (owner != null) {
                    owner.release();
                } else {
                    reservation.release();
                }
            }
        }
    }
    interface Reservation {
        void release();
    }

}
