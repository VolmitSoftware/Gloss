package art.arcane.gloss.service;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerLeasesTest {
    private final ViewerLeases leases = new ViewerLeases(VisibilityGovernor.Surface.ZONE);

    @Test
    void aRefusedViewerTearsDownWhatTheyAlreadyHadDrawn() {
        Player viewer = player();
        AtomicInteger teardowns = new AtomicInteger();
        assertTrue(leases.admit(VisibilityGovernor.passthrough(), viewer, 4, teardowns::incrementAndGet));

        assertFalse(leases.admit(refusing(), viewer, 4, teardowns::incrementAndGet));

        assertEquals(1, teardowns.get(),
            "entities left drawn that the governor no longer counts make the budget under-count");
        assertFalse(leases.holds(viewer.getUniqueId()));
    }

    @Test
    void reAdmittingReleasesTheViewersPreviousLeaseFirst() {
        Player viewer = player();
        AdmissionBudget budget = new AdmissionBudget(1);
        VisibilityGovernor governor = budgeted(budget);

        assertTrue(leases.admit(governor, viewer, 4, failOnTeardown()));
        assertTrue(leases.admit(governor, viewer, 4, failOnTeardown()),
            "a viewer re-requesting their own allotment must not be refused by the lease they hold");
        assertEquals(1, budget.active());
    }

    @Test
    void releasingAViewerGivesTheirUnitBack() {
        Player viewer = player();
        AdmissionBudget budget = new AdmissionBudget(1);
        leases.admit(budgeted(budget), viewer, 4, failOnTeardown());

        leases.release(viewer.getUniqueId());

        assertEquals(0, budget.active());
        assertFalse(leases.holds(viewer.getUniqueId()));
    }

    @Test
    void clearingGivesEveryViewersUnitBack() {
        AdmissionBudget budget = new AdmissionBudget(4);
        VisibilityGovernor governor = budgeted(budget);
        leases.admit(governor, player(), 1, failOnTeardown());
        leases.admit(governor, player(), 1, failOnTeardown());

        leases.clear();

        assertEquals(0, budget.active());
    }

    private static Runnable failOnTeardown() {
        return () -> {
            throw new AssertionError("an admitted viewer must not be torn down");
        };
    }

    private static VisibilityGovernor refusing() {
        return new VisibilityGovernor() {
            @Override
            public AdmissionBudget.Lease admit(Player viewer, Surface surface, int entities) {
                return null;
            }

            @Override
            public Tier tier(Player viewer, Surface surface, double distanceSquared) {
                return Tier.FULL;
            }
        };
    }

    private static VisibilityGovernor budgeted(AdmissionBudget budget) {
        return new VisibilityGovernor() {
            @Override
            public AdmissionBudget.Lease admit(Player viewer, Surface surface, int entities) {
                return budget.tryAcquire();
            }

            @Override
            public Tier tier(Player viewer, Surface surface, double distanceSquared) {
                return Tier.FULL;
            }
        };
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
