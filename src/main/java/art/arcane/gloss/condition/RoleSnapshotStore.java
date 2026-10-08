package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprRoleSnapshot;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

public final class RoleSnapshotStore {
    private final Runtime runtime;
    private final IntSupplier maximumReads;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public RoleSnapshotStore(Runtime runtime, IntSupplier maximumReads) {
        this.runtime = Objects.requireNonNull(runtime);
        this.maximumReads = Objects.requireNonNull(maximumReads);
    }

    public void refresh(Entity entity, Set<String> properties) {
        Entry entry = entry(entity);
        for (String property : properties) {
            entry.demand(Read.variable(property));
        }
        entry.request();
    }

    public ExprRoleSnapshot view(Entity entity) {
        Entry entry = entry(entity);
        Map<Read, Result> values = entry.values;
        return new ExprRoleSnapshot() {
            @Override
            public Object variable(String property) {
                return entry.read(values, Read.variable(property));
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return entry.read(values, new Read(name, arguments));
            }

            @Override
            public boolean ownsCurrentThread() {
                return entries.get(entry.id) == entry && runtime.owns(entity);
            }
        };
    }

    public void captureOnOwner(Entity entity, Set<String> properties) {
        if (!runtime.owns(entity)) {
            throw new IllegalStateException("Role capture requires the entity's owning region");
        }
        Entry entry = entry(entity);
        for (String property : properties) {
            entry.demand(Read.variable(property));
        }
        entry.capture();
    }

    public boolean isTracked(UUID id) {
        return entries.containsKey(id);
    }

    public void forget(UUID id) {
        entries.remove(id);
    }

    public void clear() {
        entries.clear();
    }

    private Entry entry(Entity entity) {
        if (!runtime.current(entity)) {
            throw new RoleSnapshotPendingException();
        }
        UUID id = entity.getUniqueId();
        Entry existing = entries.get(id);
        if (existing != null && existing.entity == entity) {
            return existing;
        }
        return entries.compute(id, (ignored, previous) -> {
            if (!runtime.current(entity)) {
                throw new RoleSnapshotPendingException();
            }
            return previous != null && previous.entity == entity ? previous : new Entry(entity, id);
        });
    }

    public interface Runtime {
        boolean dispatch(Entity entity, Runnable task, Runnable retired);

        ExprScope scope(Entity entity);

        boolean active(Entity entity);

        boolean owns(Entity entity);

        boolean current(Entity entity);
    }

    private record Read(String name, List<Object> arguments) {
        private Read {
            arguments = List.copyOf(arguments);
        }

        private static Read variable(String property) {
            return new Read("", List.of(property));
        }
    }

    private record Result(Object value, RuntimeException failure) {
        private Object get() {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private final class Entry {
        private final Entity entity;
        private final UUID id;
        private final Set<Read> demanded = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private volatile Map<Read, Result> values = Map.of();

        private Entry(Entity entity, UUID id) {
            this.entity = entity;
            this.id = id;
        }

        private void demand(Read read) {
            synchronized (demanded) {
                if (!demanded.contains(read) && demanded.size() >= Math.max(1, maximumReads.getAsInt())) {
                    throw new IllegalStateException("Role snapshot read limit exceeded for " + id);
                }
                demanded.add(read);
            }
        }

        private Object read(Map<Read, Result> snapshot, Read read) {
            if (entries.get(id) != this) {
                throw new RoleSnapshotPendingException();
            }
            Result result = snapshot.get(read);
            if (result != null) {
                return result.get();
            }
            demand(read);
            request();
            throw new RoleSnapshotPendingException();
        }

        private void request() {
            if (entries.get(id) != this || !scheduled.compareAndSet(false, true)) {
                return;
            }
            Runnable retired = () -> scheduled.set(false);
            if (!runtime.dispatch(entity, this::capture, retired)) {
                retired.run();
            }
        }

        private void capture() {
            try {
                if (entries.get(id) != this || !runtime.active(entity)) {
                    return;
                }
                ExprScope scope = runtime.scope(entity);
                Map<Read, Result> next = new HashMap<>(demanded.size());
                for (Read read : demanded) {
                    try {
                        Object value = read.name().isEmpty()
                            ? scope.variable(String.valueOf(read.arguments().getFirst()))
                            : scope.call(read.name(), read.arguments());
                        next.put(read, new Result(value, null));
                    } catch (RuntimeException failure) {
                        next.put(read, new Result(null, failure));
                    }
                }
                if (entries.get(id) == this && runtime.active(entity)) {
                    values = Map.copyOf(next);
                }
            } finally {
                scheduled.set(false);
            }
        }
    }
}
