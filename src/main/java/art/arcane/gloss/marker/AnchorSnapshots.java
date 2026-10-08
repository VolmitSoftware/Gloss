package art.arcane.gloss.marker;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class AnchorSnapshots implements AutoCloseable {
    private static final long TICK_NANOS = 50_000_000L;
    private final Runtime runtime;
    private final Supplier<Settings> settings;
    private final LongSupplier clock;
    private final Map<MarkerAnchor, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private boolean closed;
    private int pendingCaptures;

    public AnchorSnapshots(Gloss plugin) {
        this(new Dependencies(new EntityRuntime(plugin), () -> new Settings(
            plugin.cfg().modules().markers().anchorSnapshotTicks(),
            plugin.cfg().modules().markers().anchorMaxAgeTicks(),
            plugin.cfg().modules().markers().anchorCacheEntries()), System::nanoTime));
    }

    AnchorSnapshots(Dependencies dependencies) {
        runtime = Objects.requireNonNull(dependencies.runtime());
        settings = Objects.requireNonNull(dependencies.settings());
        clock = Objects.requireNonNull(dependencies.clock());
    }

    public Location position(MarkerAnchor anchor, World viewerWorld) {
        if (anchor.isPosition()) {
            return viewerWorld.getName().equals(anchor.world())
                ? new Location(viewerWorld, anchor.x(), anchor.y(), anchor.z()) : null;
        }
        Snapshot snapshot = snapshot(anchor);
        return snapshot != null && viewerWorld.getUID().equals(snapshot.world())
            ? new Location(viewerWorld, snapshot.x(), snapshot.y(), snapshot.z()) : null;
    }

    Snapshot snapshot(MarkerAnchor anchor) {
        Settings policy = settings.get();
        long now = clock.getAsLong();
        Entry entry;
        boolean request;
        synchronized (entries) {
            if (closed) {
                return null;
            }
            entry = entries.get(anchor);
            if (entry == null) {
                entry = new Entry();
                entries.put(anchor, entry);
            }
            while (entries.size() > policy.capacity()) {
                entries.remove(entries.keySet().iterator().next());
            }
            request = pendingCaptures < policy.capacity() && !entry.pending && (!entry.requested || now - entry.lastRequest >= policy.refreshTicks() * TICK_NANOS);
            if (request) {
                pendingCaptures++;
                entry.pending = true;
                entry.requested = true;
                entry.lastRequest = now;
            }
        }
        if (request) {
            request(anchor, entry);
        }
        synchronized (entries) {
            return !closed && entry.snapshot != null && now - entry.capturedAt <= policy.maxAgeTicks() * TICK_NANOS
                ? entry.snapshot : null;
        }
    }

    @Override
    public void close() {
        synchronized (entries) {
            closed = true;
            entries.clear();
        }
    }

    private void request(MarkerAnchor anchor, Entry entry) {
        AtomicBoolean completed = new AtomicBoolean();
        Consumer<Snapshot> finish = snapshot -> {
            if (completed.compareAndSet(false, true)) {
                complete(anchor, entry, snapshot);
            }
        };
        try {
            if (!runtime.capture(anchor, finish, () -> finish.accept(null))) {
                finish.accept(null);
            }
        } catch (RuntimeException failure) {
            finish.accept(null);
            Gloss.logExceptionStackThrottled(false, "anchor-snapshot", failure,
                "Could not capture a moving marker or waypoint anchor.");
        }
    }

    private void complete(MarkerAnchor anchor, Entry entry, Snapshot snapshot) {
        synchronized (entries) {
            pendingCaptures--;
            entry.pending = false;
            if (closed || entries.get(anchor) != entry) {
                return;
            }
            entry.snapshot = snapshot;
            entry.capturedAt = clock.getAsLong();
        }
    }

    record Dependencies(Runtime runtime, Supplier<Settings> settings, LongSupplier clock) {
    }

    record Settings(int refreshTicks, int maxAgeTicks, int capacity) {
        Settings {
            if (refreshTicks < 1 || maxAgeTicks < refreshTicks || capacity < 1) {
                throw new IllegalArgumentException("Invalid anchor snapshot limits");
            }
        }
    }

    record Snapshot(UUID world, double x, double y, double z) {
    }

    interface Runtime {
        boolean capture(MarkerAnchor anchor, Consumer<Snapshot> completed, Runnable retired);
    }

    private static final class Entry {
        private Snapshot snapshot;
        private long capturedAt;
        private long lastRequest;
        private boolean requested;
        private boolean pending;
    }

    private record EntityRuntime(Gloss plugin) implements Runtime {
        @Override
        public boolean capture(MarkerAnchor anchor, Consumer<Snapshot> completed, Runnable retired) {
            Entity entity = anchor.followsEntity() ? Bukkit.getEntity(anchor.entity())
                : Bukkit.getPlayerExact(anchor.player());
            if (entity == null) {
                completed.accept(null);
                return true;
            }
            return FoliaScheduler.runEntity(plugin, entity, () -> captureEntity(entity, completed), 0, retired);
        }

        private void captureEntity(Entity entity, Consumer<Snapshot> completed) {
            try {
                if (!entity.isValid() || entity instanceof Player player && !player.isOnline()) {
                    completed.accept(null);
                    return;
                }
                Location location = entity.getLocation();
                completed.accept(new Snapshot(location.getWorld().getUID(), location.getX(),
                    location.getY(), location.getZ()));
            } catch (RuntimeException failure) {
                completed.accept(null);
                Gloss.logExceptionStackThrottled(false, "anchor-snapshot-capture", failure,
                    "Could not read a moving anchor on its owning region.");
            }
        }
    }
}
