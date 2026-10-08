package art.arcane.gloss.service;

import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

public final class BudgetedVisibilityGovernor implements VisibilityGovernor {
    private static final Surface[] SURFACES = Surface.values();
    private final Supplier<Limits> limits;
    private final LongSupplier clock;
    private final LinkedHashMap<RequestKey, Long> pending = new LinkedHashMap<>();
    private long expired;
    private final Map<UUID, ViewerUsage> viewers = new HashMap<>();
    private final long[] surfaceUsage = new long[SURFACES.length];
    private long active;
    private long admitted;
    private long refused;

    public BudgetedVisibilityGovernor(Supplier<Limits> limits) {
        this(limits, System::nanoTime);
    }

    BudgetedVisibilityGovernor(Supplier<Limits> limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized AdmissionBudget.Lease admit(Player viewer, Surface surface, int entities) {
        Objects.requireNonNull(viewer);
        Objects.requireNonNull(surface);
        if (entities < 0) {
            throw new IllegalArgumentException("Visible entity count must not be negative");
        }
        Limits configured = limits.get();
        Policy policy = configured.policy(surface);
        UUID id = viewer.getUniqueId();
        ViewerUsage usage = viewers.get(id);
        long current = usage == null ? 0 : usage.active;
        long currentSurface = usage == null ? 0 : usage.surfaces[surface.ordinal()];
        long reserved = reservedForOthers(configured, usage, surface);
        RequestKey request = new RequestKey(id, surface);
        maintainQueue(configured.admission());
        if (entities > 0 && (!hasTurn(request, configured.admission())
            || entities > configured.perServer() - active - reservedServerForOthers(configured, surface)
            || entities > configured.perViewer() - current - reserved
            || entities > policy.perViewer() - currentSurface
            || entities > policy.perServer() - surfaceUsage[surface.ordinal()])) {
            enqueue(request, configured);
            refused++;
            return null;
        }
        if (entities > 0) {
            pending.remove(request);
        }
        ViewerUsage admittedUsage = usage == null ? new ViewerUsage() : usage;
        if (entities > 0) {
            viewers.putIfAbsent(id, admittedUsage);
        }
        admittedUsage.active += entities;
        admittedUsage.surfaces[surface.ordinal()] += entities;
        surfaceUsage[surface.ordinal()] += entities;
        active += entities;
        admitted++;
        return new AdmissionBudget.Lease(new Allocation(this, id, admittedUsage, surface, entities));
    }

    @Override
    public Tier tier(Player viewer, Surface surface, double distanceSquared) {
        Policy policy = limits.get().policy(surface);
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0
            || distanceSquared > policy.cullDistance() * policy.cullDistance()) {
            return Tier.CULLED;
        }
        if (distanceSquared > policy.reducedDistance() * policy.reducedDistance()) {
            return Tier.MINIMAL;
        }
        return distanceSquared > policy.fullDistance() * policy.fullDistance() ? Tier.REDUCED : Tier.FULL;
    }

    @Override
    public synchronized AdmissionBudget.Lease renew(AdmissionBudget.Lease previous, Player viewer,
                                                   Surface surface, int entities) {
        if (previous == null) {
            return admit(viewer, surface, entities);
        }
        if (!(previous.reservation() instanceof Allocation allocation) || allocation.owner != this
            || !allocation.viewer.equals(viewer.getUniqueId()) || allocation.surface != surface) {
            throw new IllegalArgumentException("A visibility lease can only be renewed by its viewer, surface and governor");
        }
        if (entities < 0) {
            throw new IllegalArgumentException("Visible entity count must not be negative");
        }
        Limits configured = limits.get();
        Policy policy = configured.policy(surface);
        ViewerUsage usage = allocation.entities == 0 ? viewers.getOrDefault(allocation.viewer, allocation.usage) : allocation.usage;
        allocation.usage = usage;
        long delta = (long) entities - allocation.entities;
        long reserved = reservedForOthers(configured, usage, surface);
        RequestKey request = new RequestKey(allocation.viewer, surface);
        maintainQueue(configured.admission());
        if (delta > 0 && (!hasTurn(request, configured.admission())
            || delta > configured.perServer() - active - reservedServerForOthers(configured, surface)
            || delta > configured.perViewer() - usage.active - reserved
            || delta > policy.perViewer() - usage.surfaces[surface.ordinal()]
            || delta > policy.perServer() - surfaceUsage[surface.ordinal()])) {
            enqueue(request, configured);
            refused++;
            return null;
        }
        if (delta > 0) {
            pending.remove(request);
        }
        usage.active += delta;
        usage.surfaces[surface.ordinal()] += delta;
        surfaceUsage[surface.ordinal()] += delta;
        active += delta;
        allocation.entities = entities;
        if (usage.active == 0) {
            viewers.remove(allocation.viewer, usage);
        } else {
            viewers.putIfAbsent(allocation.viewer, usage);
        }
        return previous;
    }

    public synchronized Snapshot snapshot() {
        maintainQueue(limits.get().admission());
        Map<Surface, Long> surfaces = new EnumMap<>(Surface.class);
        for (Surface surface : SURFACES) {
            surfaces.put(surface, surfaceUsage[surface.ordinal()]);
        }
        return new Snapshot(active, viewers.size(), admitted, refused, surfaces, pending.size(), expired);
    }

    private void maintainQueue(AdmissionPolicy policy) {
        if (policy.mode() == AdmissionMode.REJECT) {
            pending.clear();
            return;
        }
        long now = clock.getAsLong();
        long lifetime = policy.timeoutTicks() * 50_000_000L;
        while (!pending.isEmpty() && (pending.size() > policy.maxPending()
            || now - pending.firstEntry().getValue() >= lifetime)) {
            pending.pollFirstEntry();
            expired++;
        }
    }

    private boolean hasTurn(RequestKey request, AdmissionPolicy policy) {
        return policy.mode() == AdmissionMode.REJECT || pending.isEmpty()
            || pending.firstEntry().getKey().equals(request);
    }

    private void enqueue(RequestKey request, Limits configured) {
        AdmissionPolicy policy = configured.admission();
        if (policy.mode() == AdmissionMode.FIFO && pending.size() < policy.maxPending()) {
            pending.putIfAbsent(request, clock.getAsLong());
        }
    }

    private long reservedServerForOthers(Limits configured, Surface requested) {
        long reserved = 0;
        for (Surface surface : SURFACES) {
            if (surface != requested) {
                reserved += Math.max(0, configured.policy(surface).reservedPerServer() - surfaceUsage[surface.ordinal()]);
            }
        }
        return reserved;
    }

    private long reservedForOthers(Limits configured, ViewerUsage usage, Surface requested) {
        long reserved = 0;
        for (Surface surface : SURFACES) {
            if (surface != requested) {
                long current = usage == null ? 0 : usage.surfaces[surface.ordinal()];
                reserved += Math.max(0, configured.policy(surface).reservedPerViewer() - current);
            }
        }
        return reserved;
    }

    private synchronized void release(UUID id, ViewerUsage usage, Surface surface, int entities) {
        usage.active -= entities;
        usage.surfaces[surface.ordinal()] -= entities;
        surfaceUsage[surface.ordinal()] -= entities;
        active -= entities;
        if (usage.active == 0) {
            viewers.remove(id, usage);
        }
    }

    public record Limits(int perServer, int perViewer, Policy defaults, Map<Surface, Policy> surfaces, AdmissionPolicy admission) {
        public Limits {
            if (perServer < 1 || perViewer < 1) {
                throw new IllegalArgumentException("Visible entity budgets must be positive");
            }
            Objects.requireNonNull(defaults);
            Objects.requireNonNull(admission);
            surfaces = Map.copyOf(surfaces);
            long reserved = 0;
            long reservedServer = 0;
            for (Surface surface : SURFACES) {
                reserved += surfaces.getOrDefault(surface, defaults).reservedPerViewer();
                reservedServer += surfaces.getOrDefault(surface, defaults).reservedPerServer();
            }
            if (reservedServer > perServer) {
                throw new IllegalArgumentException("Surface reservations exceed the server budget");
            }
            if (reserved > perViewer) {
                throw new IllegalArgumentException("Surface reservations exceed the viewer budget");
            }
        }

        public Policy policy(Surface surface) {
            return surfaces.getOrDefault(surface, defaults);
        }
    }

    public record Policy(int perServer, int perViewer, int reservedPerViewer, int reservedPerServer, double fullDistance,
                         double reducedDistance, double cullDistance) {
        public Policy {
            if (perServer < 1 || perViewer < 1 || reservedPerViewer < 0 || reservedPerViewer > perViewer
                || reservedPerServer < 0 || reservedPerServer > perServer) {
                throw new IllegalArgumentException("Surface budgets must be positive and reservations must fit");
            }
            if (!Double.isFinite(fullDistance) || !Double.isFinite(reducedDistance)
                || !Double.isFinite(cullDistance) || fullDistance < 0 || reducedDistance < fullDistance
                || cullDistance < reducedDistance) {
                throw new IllegalArgumentException("Visibility distances must be finite and ordered");
            }
        }
    }

    public record Snapshot(long visibleEntities, int viewers, long admissions, long refusals,
                           Map<Surface, Long> surfaces, int pending, long expired) {
        public Snapshot {
            surfaces = Map.copyOf(surfaces);
        }
    }

    public enum AdmissionMode {
        REJECT,
        FIFO
    }

    public record AdmissionPolicy(AdmissionMode mode, int maxPending, int timeoutTicks) {
        public AdmissionPolicy {
            Objects.requireNonNull(mode);
            if (maxPending < 1 || maxPending > 65536 || timeoutTicks < 1 || timeoutTicks > 72000) {
                throw new IllegalArgumentException("Admission queue limits are outside their supported ranges");
            }
        }
    }

    private record RequestKey(UUID viewer, Surface surface) {
    }

    private static final class Allocation implements AdmissionBudget.Reservation {
        private final BudgetedVisibilityGovernor owner;
        private final UUID viewer;
        private ViewerUsage usage;
        private final Surface surface;
        private int entities;

        private Allocation(BudgetedVisibilityGovernor owner, UUID viewer, ViewerUsage usage, Surface surface, int entities) {
            this.owner = owner;
            this.viewer = viewer;
            this.usage = usage;
            this.surface = surface;
            this.entities = entities;
        }

        @Override
        public void release() {
            synchronized (owner) {
                owner.release(viewer, usage, surface, entities);
                entities = 0;
            }
        }
    }

    private static final class ViewerUsage {
        private final long[] surfaces = new long[SURFACES.length];
        private long active;
    }
}
