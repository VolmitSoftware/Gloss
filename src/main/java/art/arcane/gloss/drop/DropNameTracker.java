package art.arcane.gloss.drop;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class DropNameTracker {
    private final Set<UUID> named = ConcurrentHashMap.newKeySet();
    private final Predicate<UUID> alive;

    private Iterator<UUID> cursor;

    DropNameTracker(Predicate<UUID> alive) {
        this.alive = alive;
    }

    DropNameTracker() {
        this(entityId -> true);
    }

    void track(UUID entityId) {
        named.add(entityId);
    }

    void forget(UUID entityId) {
        named.remove(entityId);
    }

    void clear() {
        named.clear();
        cursor = null;
    }

    int size() {
        return named.size();
    }

    void prune(int budget) {
        if (named.isEmpty()) {
            cursor = null;
            return;
        }

        Iterator<UUID> active = cursor;
        if (active == null || !active.hasNext()) {
            active = named.iterator();
            cursor = active;
        }

        int remaining = budget;
        while (remaining > 0 && active.hasNext()) {
            remaining--;
            UUID entityId = active.next();
            if (!alive.test(entityId)) {
                active.remove();
            }
        }
    }

    void inspect(int budget, Consumer<UUID> inspector) {
        if (named.isEmpty()) {
            cursor = null;
            return;
        }

        Iterator<UUID> active = cursor;
        if (active == null || !active.hasNext()) {
            active = named.iterator();
            cursor = active;
        }

        int remaining = budget;
        while (remaining > 0 && active.hasNext()) {
            remaining--;
            inspector.accept(active.next());
        }
    }
}
