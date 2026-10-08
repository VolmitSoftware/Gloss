package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.service.VisibilityGovernor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import java.util.Objects;

public final class ViewerDisplayBudget {
    private final Gloss plugin;
    private final Supplier<VisibilityGovernor.Surface> surface;
    private final Map<UUID, State> viewers = new ConcurrentHashMap<>();
    private boolean closed;
    private volatile BiConsumer<UUID, Boolean> visibilityObserver;

    public ViewerDisplayBudget(Gloss plugin, Supplier<VisibilityGovernor.Surface> surface) {
        this.plugin = plugin;
        this.surface = surface;
    }

    public void visibilityObserver(BiConsumer<UUID, Boolean> observer) {
        visibilityObserver = Objects.requireNonNull(observer);
    }

    public boolean show(Player viewer, List<? extends Entity> displays) {
        State state;
        synchronized (this) {
            if (closed) {
                return false;
            }
            UUID viewerId = viewer.getUniqueId();
            state = viewers.get(viewerId);
            if (state == null) {
                state = viewers.computeIfAbsent(viewerId, ignored -> create(viewer));
            }
        }
        state.group.begin();
        try {
            Entity singleton = displays.size() == 1 && state.displays.size() == 1 ? displays.getFirst() : null;
            UUID singletonId = singleton == null ? null : singleton.getUniqueId();
            if (singleton != null && state.displays.get(singletonId) == singleton) {
                state.group.show(singletonId);
            } else {
                Set<UUID> current = new HashSet<>(displays.size());
                for (Entity display : displays) {
                    UUID id = display.getUniqueId();
                    current.add(id);
                    state.displays.put(id, display);
                    state.group.show(id);
                }
                for (UUID id : List.copyOf(state.displays.keySet())) {
                    if (!current.contains(id)) {
                        state.group.hide(id, true);
                    }
                }
            }
        } finally {
            state.group.end();
        }
        return state.group.visible();
    }

    public void hide(Player viewer) {
        UUID id = viewer.getUniqueId();
        State state = viewers.get(id);
        if (state != null) {
            state.group.close();
            viewers.remove(id, state);
        }
    }

    public void forget(UUID viewerId) {
        State state = viewers.remove(viewerId);
        if (state != null) {
            state.group.disconnected();
        }
    }

    public boolean isShown(UUID viewerId) {
        State state = viewers.get(viewerId);
        return state != null && state.group.visible();
    }

    public void retain(Set<UUID> retained) {
        for (Map.Entry<UUID, State> entry : viewers.entrySet()) {
            if (!retained.contains(entry.getKey()) && viewers.remove(entry.getKey(), entry.getValue())) {
                DisplayEntityManager.retire(plugin, entry.getValue().group);
            }
        }
    }

    public synchronized void close() {
        closed = true;
        clear();
    }

    public synchronized void clear() {
        for (State state : viewers.values()) {
            DisplayEntityManager.retire(plugin, state.group);
        }
        viewers.clear();
    }

    private State create(Player viewer) {
        Map<UUID, Entity> displays = new HashMap<>();
        DisplayEntityGroup.Transport transport = new WorldTransport(plugin, viewer, displays);
        DisplayEntityGroup group = new DisplayEntityGroup(new DisplayEntityGroup.Options(viewer, surface.get(),
            plugin::governor, transport));
        UUID viewerId = viewer.getUniqueId();
        group.visibilityObserver(visible -> {
            BiConsumer<UUID, Boolean> observer = visibilityObserver;
            if (observer != null) {
                observer.accept(viewerId, visible);
            }
        });
        return new State(group, displays);
    }

    private record State(DisplayEntityGroup group, Map<UUID, Entity> displays) {
    }

    private record WorldTransport(Gloss plugin, Player viewer, Map<UUID, Entity> displays)
        implements DisplayEntityGroup.Transport {
        @Override
        public boolean spawn(UUID handle) {
            Entity display = displays.get(handle);
            if (display == null || !viewer.isOnline()) {
                return false;
            }
            viewer.showEntity(plugin, display);
            return true;
        }

        @Override
        public void remove(List<UUID> handles, boolean delete) {
            for (UUID handle : handles) {
                Entity display = displays.get(handle);
                if (display != null && viewer.isOnline()) {
                    viewer.hideEntity(plugin, display);
                }
                if (delete) {
                    displays.remove(handle);
                }
            }
        }
    }
}
