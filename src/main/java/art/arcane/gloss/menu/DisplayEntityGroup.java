package art.arcane.gloss.menu;

import art.arcane.gloss.service.AdmissionBudget;
import art.arcane.gloss.service.VisibilityGovernor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class DisplayEntityGroup {
    private final Player viewer;
    private final Supplier<VisibilityGovernor> governors;
    private final Transport transport;
    private final Set<UUID> desired = new LinkedHashSet<>();
    private final Set<UUID> visible = new LinkedHashSet<>();
    private final Set<UUID> deleted = new LinkedHashSet<>();
    private VisibilityGovernor.Surface surface;
    private VisibilityGovernor owner;
    private AdmissionBudget.Lease lease;
    private int batchDepth;
    private boolean culled;
    private volatile Consumer<Boolean> visibilityObserver;
    private volatile boolean shown;
    private volatile int shownCount;

    public DisplayEntityGroup(Options options) {
        this.viewer = Objects.requireNonNull(options.viewer());
        this.governors = Objects.requireNonNull(options.governors());
        this.transport = Objects.requireNonNull(options.transport());
        this.surface = Objects.requireNonNull(options.surface());
    }

    public Player viewer() {
        return viewer;
    }

    public void begin() {
        batchDepth++;
    }

    public void end() {
        if (batchDepth < 1) {
            throw new IllegalStateException("Display group batch is not open");
        }
        if (--batchDepth == 0) {
            refresh();
        }
    }

    public void batch(Runnable work) {
        begin();
        try {
            work.run();
        } finally {
            end();
        }
    }

    public void surface(VisibilityGovernor.Surface replacement) {
        if (lease != null) {
            throw new IllegalStateException("A visible display group cannot change surfaces");
        }
        surface = Objects.requireNonNull(replacement);
    }

    public VisibilityGovernor.Surface surface() {
        return surface;
    }

    public void culled(boolean value) {
        culled = value;
    }

    public void visibilityObserver(Consumer<Boolean> observer) {
        visibilityObserver = Objects.requireNonNull(observer);
    }

    public boolean visible() {
        return shown;
    }

    public int visibleCount() {
        return shownCount;
    }

    public void show(UUID handle) {
        deleted.remove(handle);
        desired.add(handle);
        if (batchDepth == 0) {
            refresh();
        }
    }

    public void hide(UUID handle, boolean delete) {
        desired.remove(handle);
        if (delete) {
            deleted.add(handle);
        }
        if (batchDepth == 0) {
            refresh();
        }
    }

    public void refresh() {
        try {
            reconcile();
        } finally {
            shownCount = visible.size();
            publishVisibility(!culled && !desired.isEmpty() && visible.size() == desired.size() && visible.containsAll(desired));
        }
    }

    private void reconcile() {
        if (batchDepth != 0) {
            return;
        }
        if (!deleted.isEmpty()) {
            List<UUID> removing = List.copyOf(deleted);
            transport.remove(removing, true);
            visible.removeAll(removing);
            deleted.clear();
        }
        List<UUID> hidden = new ArrayList<>();
        for (UUID handle : visible) {
            if (culled || !desired.contains(handle)) {
                hidden.add(handle);
            }
        }
        if (!hidden.isEmpty()) {
            transport.remove(hidden, false);
            visible.removeAll(hidden);
        }
        int count = culled ? 0 : desired.size();
        if (count == 0) {
            release();
            return;
        }
        VisibilityGovernor governor = governors.get();
        if (owner != null && governor != owner) {
            hideVisible();
            release();
        }
        AdmissionBudget.Lease acquired = governor.renew(lease, viewer, surface, count);
        if (acquired == null) {
            hideVisible();
            release();
            return;
        }
        owner = governor;
        lease = acquired;
        for (UUID handle : desired) {
            if (!visible.contains(handle)) {
                if (!transport.spawn(handle)) {
                    hideVisible();
                    release();
                    return;
                }
                visible.add(handle);
            }
        }
    }

    public void close() {
        deleted.addAll(desired);
        deleted.addAll(visible);
        desired.clear();
        refresh();
    }

    public void disconnected() {
        desired.clear();
        visible.clear();
        deleted.clear();
        shownCount = 0;
        release();
        publishVisibility(false);
    }

    private void publishVisibility(boolean value) {
        boolean previous = shown;
        shown = value;
        Consumer<Boolean> observer = visibilityObserver;
        if (previous != value && observer != null) {
            observer.accept(value);
        }
    }

    private void hideVisible() {
        if (!visible.isEmpty()) {
            transport.remove(List.copyOf(visible), false);
            visible.clear();
        }
    }

    private void release() {
        if (lease != null) {
            lease.close();
            lease = null;
        }
        owner = null;
    }

    public record Options(Player viewer, VisibilityGovernor.Surface surface, Supplier<VisibilityGovernor> governors,
                          Transport transport) {
    }

    public interface Transport {
        boolean spawn(UUID handle);

        void remove(List<UUID> handles, boolean delete);
    }
}
