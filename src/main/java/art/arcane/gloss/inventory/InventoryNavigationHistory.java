package art.arcane.gloss.inventory;

import art.arcane.gloss.enums.NavigationMode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One window stack per viewer, mirroring the hologram-menu history. Paging never touches it: a page
 * change stays inside the window it was clicked in, so backing out of a shop still lands where the
 * viewer came from rather than on page one.
 */
public final class InventoryNavigationHistory {
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Notes that this window is now the one on screen. */
    public void record(UUID player, String id) {
        Objects.requireNonNull(id, "id");
        Session session = sessions.computeIfAbsent(player, key -> new Session());
        if (session.root == null) {
            session.root = id;
        }
        session.current = id;
    }

    /** @return the window id this navigation opens, or null when it opens nothing */
    public String resolve(UUID player, NavigationMode mode, String target) {
        Session session = sessions.get(player);
        return switch (mode) {
            case PUSH, REPLACE -> target;
            case BACK -> session == null ? null : session.entries.peekFirst();
            case HOME -> session == null ? null : session.root;
            case CLOSE, PAGE -> null;
        };
    }

    /** Applies a navigation that has already happened; {@code previousId} is the window being left. */
    public void commit(UUID player, NavigationMode mode, String previousId) {
        Session session = sessions.computeIfAbsent(player, key -> new Session());
        switch (mode) {
            case PUSH -> {
                if (previousId == null) {
                    session.entries.clear();
                } else {
                    session.entries.addFirst(previousId);
                }
            }
            case BACK -> session.entries.pollFirst();
            case HOME -> session.entries.clear();
            case REPLACE, CLOSE, PAGE -> {
            }
        }
    }

    public String current(UUID player) {
        Session session = sessions.get(player);
        return session == null ? null : session.current;
    }

    public void forget(UUID player) {
        sessions.remove(player);
    }

    public void clear() {
        sessions.clear();
    }

    private static final class Session {
        private final Deque<String> entries = new ArrayDeque<>();
        private String root;
        private String current;
    }
}
