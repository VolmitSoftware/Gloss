package art.arcane.gloss.drop;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a real-drop presentation has actually applied to each viewer. Entries are written by the
 * viewer's own task after the hide/show landed, so a task that never ran leaves nothing behind and
 * a viewer that quit is dispatched from scratch when it returns.
 */
final class RealDropAudienceMemo {
    static final int REFRESH_TICKS = 20;

    private final Map<UUID, Applied> applied = new ConcurrentHashMap<>();

    boolean refreshRequired(UUID viewerId, int visualsRevision, long tick) {
        Applied current = applied.get(viewerId);
        return current == null || current.visualsRevision() != visualsRevision
            || tick - current.tick() >= REFRESH_TICKS;
    }

    boolean changed(UUID viewerId, boolean visible, int visualsRevision) {
        Applied current = applied.get(viewerId);
        return current == null || current.visible() != visible
            || current.visualsRevision() != visualsRevision;
    }

    void applied(UUID viewerId, boolean visible, int visualsRevision, long tick) {
        applied.put(viewerId, new Applied(visible, visualsRevision, tick));
    }

    Boolean visibility(UUID viewerId) {
        Applied current = applied.get(viewerId);
        return current == null ? null : current.visible();
    }

    void forget(UUID viewerId) {
        applied.remove(viewerId);
    }

    Set<UUID> viewers() {
        return applied.keySet();
    }

    boolean isEmpty() {
        return applied.isEmpty();
    }

    void clear() {
        applied.clear();
    }

    private record Applied(boolean visible, int visualsRevision, long tick) {
    }
}
