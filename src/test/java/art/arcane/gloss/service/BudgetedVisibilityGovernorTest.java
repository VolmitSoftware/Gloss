package art.arcane.gloss.service;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static art.arcane.gloss.service.VisibilityGovernor.Surface.HOLOGRAM;
import static art.arcane.gloss.service.VisibilityGovernor.Surface.MENU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BudgetedVisibilityGovernorTest {
    @Test
    void failedGrowthRetainsTheOldReservationUntilRemovalSucceeds() {
        BudgetedVisibilityGovernor governor = governor(10, 10, Map.of());
        Player viewer = player();
        AdmissionBudget.Lease lease = governor.admit(viewer, MENU, 8);
        assertNull(governor.renew(lease, viewer, MENU, 11));
        assertEquals(8L, governor.snapshot().visibleEntities());
        assertNull(governor.admit(player(), HOLOGRAM, 3));
        assertEquals(lease, governor.renew(lease, viewer, MENU, 5));
        assertEquals(5L, governor.snapshot().visibleEntities());
        lease.close();
        assertEquals(0L, governor.snapshot().visibleEntities());
    }

    @Test
    void zeroReservationCanGrowAlongsideAnotherReservationForTheSameViewer() {
        BudgetedVisibilityGovernor governor = governor(10, 10, Map.of());
        Player viewer = player();
        AdmissionBudget.Lease zero = governor.admit(viewer, MENU, 0);
        AdmissionBudget.Lease other = governor.admit(viewer, MENU, 3);
        assertNotNull(governor.renew(zero, viewer, MENU, 4));
        assertEquals(7L, governor.snapshot().visibleEntities());
        assertEquals(1, governor.snapshot().viewers());
        other.close();
        zero.close();
        assertEquals(0L, governor.snapshot().visibleEntities());
        assertEquals(0, governor.snapshot().viewers());
    }

    @Test
    void countsRequestedEntitiesAcrossViewersAndReleasesExactlyOnce() {
        BudgetedVisibilityGovernor governor = governor(10, 8, Map.of());
        AdmissionBudget.Lease first = governor.admit(player(), HOLOGRAM, 7);
        assertNotNull(first);
        assertNull(governor.admit(player(), HOLOGRAM, 4));
        AdmissionBudget.Lease second = governor.admit(player(), MENU, 3);
        assertNotNull(second);
        assertEquals(10L, governor.snapshot().visibleEntities());
        first.close();
        first.close();
        assertEquals(3L, governor.snapshot().visibleEntities());
        second.close();
        assertEquals(0, governor.snapshot().viewers());
        assertEquals(0L, governor.snapshot().visibleEntities());
    }

    @Test
    void reservesViewerCapacityForAnIdleSurface() {
        BudgetedVisibilityGovernor governor = governor(100, 10, Map.of(MENU, policy(100, 6, 4)));
        Player viewer = player();
        assertNull(governor.admit(viewer, HOLOGRAM, 7));
        AdmissionBudget.Lease holograms = governor.admit(viewer, HOLOGRAM, 6);
        assertNotNull(holograms);
        AdmissionBudget.Lease menu = governor.admit(viewer, MENU, 4);
        assertNotNull(menu);
        assertNull(governor.admit(viewer, MENU, 1));
        menu.close();
        assertNull(governor.admit(viewer, HOLOGRAM, 1));
        holograms.close();
    }

    @Test
    void appliesSurfaceLimitsAcrossViewers() {
        BudgetedVisibilityGovernor governor = governor(100, 20, Map.of(MENU, policy(5, 4, 0)));
        AdmissionBudget.Lease first = governor.admit(player(), MENU, 4);
        assertNotNull(first);
        assertNull(governor.admit(player(), MENU, 2));
        AdmissionBudget.Lease last = governor.admit(player(), MENU, 1);
        assertNotNull(last);
        first.close();
        last.close();
    }

    @Test
    void changedLimitsApplyToNewAdmissionsWithoutLosingExistingAccounting() {
        AtomicReference<BudgetedVisibilityGovernor.Limits> limits = new AtomicReference<>(
            new BudgetedVisibilityGovernor.Limits(100, 20, policy(100, 20, 0), Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
        BudgetedVisibilityGovernor governor = new BudgetedVisibilityGovernor(limits::get);
        AdmissionBudget.Lease lease = governor.admit(player(), MENU, 20);
        limits.set(new BudgetedVisibilityGovernor.Limits(10, 10, policy(10, 10, 0), Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
        assertNull(governor.admit(player(), MENU, 1));
        lease.close();
        assertEquals(0L, governor.snapshot().visibleEntities());
        AdmissionBudget.Lease next = governor.admit(player(), MENU, 10);
        assertNotNull(next);
        next.close();
    }

    @Test
    void concurrentAdmissionsCannotExceedTheServerBudget() throws Exception {
        BudgetedVisibilityGovernor governor = governor(50, 50, Map.of());
        List<Future<AdmissionBudget.Lease>> attempts = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 100; index++) {
                Player viewer = player();
                attempts.add(executor.submit(() -> governor.admit(viewer, HOLOGRAM, 3)));
            }
            List<AdmissionBudget.Lease> leases = new ArrayList<>();
            for (Future<AdmissionBudget.Lease> attempt : attempts) {
                AdmissionBudget.Lease lease = attempt.get();
                if (lease != null) {
                    leases.add(lease);
                }
            }
            assertEquals(16, leases.size());
            assertEquals(48L, governor.snapshot().visibleEntities());
            for (AdmissionBudget.Lease lease : leases) {
                lease.close();
            }
        }
        assertEquals(0L, governor.snapshot().visibleEntities());
        assertEquals(0, governor.snapshot().viewers());
    }

    @Test
    void distanceTiersAndInvalidInputsAreExplicit() {
        BudgetedVisibilityGovernor governor = governor(100, 20, Map.of());
        Player viewer = player();
        assertEquals(VisibilityGovernor.Tier.FULL, governor.tier(viewer, MENU, 16));
        assertEquals(VisibilityGovernor.Tier.REDUCED, governor.tier(viewer, MENU, 100));
        assertEquals(VisibilityGovernor.Tier.MINIMAL, governor.tier(viewer, MENU, 400));
        assertEquals(VisibilityGovernor.Tier.CULLED, governor.tier(viewer, MENU, 1600));
        assertEquals(VisibilityGovernor.Tier.CULLED, governor.tier(viewer, MENU, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> governor.admit(viewer, MENU, -1));
        assertThrows(IllegalArgumentException.class, () -> governor(10, 3, Map.of(MENU, policy(10, 4, 4))));
    }

    @Test
    void fifoWaitersReceiveReleasedCapacityBeforeNewViewers() {
        AtomicLong clock = new AtomicLong();
        BudgetedVisibilityGovernor.Limits limits = new BudgetedVisibilityGovernor.Limits(5, 5,
            policy(5, 5, 0), Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(
                BudgetedVisibilityGovernor.AdmissionMode.FIFO, 4, 100));
        BudgetedVisibilityGovernor governor = new BudgetedVisibilityGovernor(() -> limits, clock::get);
        AdmissionBudget.Lease active = governor.admit(player(), MENU, 5);
        Player first = player();
        Player second = player();
        assertNull(governor.admit(first, MENU, 5));
        assertNull(governor.admit(second, HOLOGRAM, 5));
        active.close();
        assertNull(governor.admit(second, HOLOGRAM, 5));
        AdmissionBudget.Lease admitted = governor.admit(first, MENU, 5);
        assertNotNull(admitted);
        assertEquals(1, governor.snapshot().pending());
        admitted.close();
        assertNotNull(governor.admit(second, HOLOGRAM, 5));
        assertEquals(0, governor.snapshot().pending());
    }

    @Test
    void fifoIsBoundedAndRetriesCannotExtendAnAbandonedTurn() {
        AtomicLong clock = new AtomicLong();
        BudgetedVisibilityGovernor.Limits limits = new BudgetedVisibilityGovernor.Limits(5, 5,
            policy(5, 5, 0), Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(
                BudgetedVisibilityGovernor.AdmissionMode.FIFO, 1, 20));
        BudgetedVisibilityGovernor governor = new BudgetedVisibilityGovernor(() -> limits, clock::get);
        AdmissionBudget.Lease active = governor.admit(player(), MENU, 5);
        Player first = player();
        Player second = player();
        assertNull(governor.admit(first, MENU, 5));
        clock.set(500_000_000L);
        assertNull(governor.admit(first, MENU, 5));
        assertNull(governor.admit(second, MENU, 5));
        assertEquals(1, governor.snapshot().pending());
        active.close();
        clock.set(1_000_000_000L);
        assertNotNull(governor.admit(second, MENU, 5));
        assertEquals(1, governor.snapshot().expired());
    }

    @Test
    void serverReservationsProtectCapacityAcrossDifferentViewers() {
        BudgetedVisibilityGovernor.Policy menu = new BudgetedVisibilityGovernor.Policy(10, 10, 0, 4, 8, 16, 32);
        BudgetedVisibilityGovernor governor = governor(10, 10, Map.of(MENU, menu));
        assertNull(governor.admit(player(), HOLOGRAM, 7));
        assertNotNull(governor.admit(player(), HOLOGRAM, 6));
        assertNotNull(governor.admit(player(), MENU, 4));
        assertEquals(10, governor.snapshot().visibleEntities());
        assertThrows(IllegalArgumentException.class, () -> governor(3, 10, Map.of(MENU, menu)));
    }

    @Test
    void shrinkingAlwaysReturnsCapacityEvenAfterLimitsAreReduced() {
        AtomicReference<BudgetedVisibilityGovernor.Limits> limits = new AtomicReference<>(
            new BudgetedVisibilityGovernor.Limits(100, 100, policy(100, 100, 0), Map.of(),
                new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 1, 1)));
        BudgetedVisibilityGovernor governor = new BudgetedVisibilityGovernor(limits::get);
        Player viewer = player();
        AdmissionBudget.Lease lease = governor.admit(viewer, MENU, 100);
        limits.set(new BudgetedVisibilityGovernor.Limits(1, 1, policy(1, 1, 0), Map.of(),
            new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 1, 1)));
        assertEquals(lease, governor.renew(lease, viewer, MENU, 50));
        assertEquals(50, governor.snapshot().visibleEntities());
        lease.close();
        assertEquals(0, governor.snapshot().visibleEntities());
        assertThrows(IllegalArgumentException.class, () -> governor.renew(lease, viewer, MENU, 1));
    }

    private static BudgetedVisibilityGovernor governor(int server, int viewer,
                                                       Map<VisibilityGovernor.Surface, BudgetedVisibilityGovernor.Policy> policies) {
        BudgetedVisibilityGovernor.Limits limits = new BudgetedVisibilityGovernor.Limits(server, viewer,
            policy(server, viewer, 0), policies, new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100));
        return new BudgetedVisibilityGovernor(() -> limits);
    }

    private static BudgetedVisibilityGovernor.Policy policy(int server, int viewer, int reserve) {
        return new BudgetedVisibilityGovernor.Policy(server, viewer, reserve, 0, 8, 16, 32);
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
