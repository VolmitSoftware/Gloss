package art.arcane.gloss.surface;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class SurfaceQueue<T> {
    private final ArrayDeque<Pending<T>> pending = new ArrayDeque<>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    private Active<T> active;

    public synchronized Outcome offer(Request<T> request, long tick) {
        advance(tick);
        SurfaceDispatchPolicy policy = request.policy();
        Long previous = cooldowns.get(request.purpose());
        if (previous != null && tick - previous < policy.cooldownTicks()) {
            return Outcome.COOLDOWN;
        }
        if (duplicate(request)) {
            return Outcome.DUPLICATE;
        }
        if (active == null || !policy.mode().equals("drop") && preempts(request)) {
            activate(request, tick);
            accepted(request, tick);
            return Outcome.STARTED;
        }
        if (!policy.mode().equals("queue")) {
            return Outcome.REJECTED;
        }
        if (pending.size() >= policy.maxPending()) {
            if (policy.overflow().equals("reject")) {
                return Outcome.REJECTED;
            }
            while (pending.size() >= policy.maxPending()) {
                pending.removeFirst();
            }
        }
        pending.addLast(new Pending<>(request, tick + policy.expireTicks()));
        accepted(request, tick);
        return Outcome.QUEUED;
    }

    public synchronized Active<T> advance(long tick) {
        pending.removeIf(entry -> tick >= entry.expiresAt());
        if (active != null && tick >= active.endsAt()) {
            active = null;
        }
        if (active == null && !pending.isEmpty()) {
            activate(pending.removeFirst().request(), tick);
        }
        return active;
    }

    public synchronized void remove(Predicate<Request<T>> predicate) {
        pending.removeIf(entry -> predicate.test(entry.request()));
        if (active != null && predicate.test(active.request())) {
            active = null;
        }
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized void clear() {
        active = null;
        pending.clear();
        cooldowns.clear();
    }

    private void activate(Request<T> request, long tick) {
        active = new Active<>(request, tick, tick + request.durationTicks());
    }

    private void accepted(Request<T> request, long tick) {
        cooldowns.remove(request.purpose());
        cooldowns.put(request.purpose(), tick);
        if (cooldowns.size() > 4096) {
            Iterator<String> oldest = cooldowns.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private boolean preempts(Request<T> request) {
        return switch (request.policy().preempt()) {
            case "always" -> true;
            case "higher" -> request.priority() > active.request().priority();
            default -> false;
        };
    }

    private boolean duplicate(Request<T> request) {
        String mode = request.policy().deduplicate();
        if (mode.equals("none")) {
            return false;
        }
        if (active != null && same(active.request(), request, mode)) {
            return true;
        }
        for (Pending<T> entry : pending) {
            if (same(entry.request(), request, mode)) {
                return true;
            }
        }
        return false;
    }

    private boolean same(Request<T> left, Request<T> right, String mode) {
        return mode.equals("purpose") ? left.purpose().equals(right.purpose()) : left.content().equals(right.content());
    }

    public enum Outcome {
        STARTED, QUEUED, REJECTED, COOLDOWN, DUPLICATE
    }

    public record Request<T>(String purpose, String content, int priority, int durationTicks,
                             SurfaceDispatchPolicy policy, T value) {
        public Request {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(value, "value");
            if (durationTicks < 1 || durationTicks > 1728000) {
                throw new IllegalArgumentException("surface duration must be between 1 and 1728000 ticks");
            }
        }
    }

    public record Active<T>(Request<T> request, long startedAt, long endsAt) {
    }

    private record Pending<T>(Request<T> request, long expiresAt) {
    }
}
