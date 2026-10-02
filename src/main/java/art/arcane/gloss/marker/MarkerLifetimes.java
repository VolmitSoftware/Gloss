package art.arcane.gloss.marker;

import java.util.HashMap;
import java.util.Map;

final class MarkerLifetimes {
    private final Map<String, Lifetime> markers = new HashMap<>();

    boolean active(MarkerSpec spec, long tick) {
        if (spec.lifetimeTicks() == 0L) {
            markers.remove(spec.id());
            return true;
        }
        Lifetime lifetime = markers.get(spec.id());
        if (lifetime == null || lifetime.durationTicks != spec.lifetimeTicks()) {
            lifetime = new Lifetime(spec.lifetimeTicks(), tick);
            markers.put(spec.id(), lifetime);
        }
        lifetime.lastSeenTick = tick;
        return tick - lifetime.startedTick < spec.lifetimeTicks();
    }

    void retireAbsent(long tick) {
        markers.values().removeIf(lifetime -> lifetime.lastSeenTick != tick);
    }

    private static final class Lifetime {
        private final long durationTicks;
        private final long startedTick;
        private long lastSeenTick;

        private Lifetime(long durationTicks, long tick) {
            this.durationTicks = durationTicks;
            this.startedTick = tick;
            this.lastSeenTick = tick;
        }
    }
}
