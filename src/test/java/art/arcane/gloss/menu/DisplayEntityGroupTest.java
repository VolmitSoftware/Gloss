package art.arcane.gloss.menu;

import art.arcane.gloss.service.BudgetedVisibilityGovernor;
import art.arcane.gloss.service.VisibilityGovernor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayEntityGroupTest {
    @Test
    void aGroupCannotPartiallyAppearAndRetriesWithoutRebuildingHandles() {
        BudgetedVisibilityGovernor governor = governor(3);
        Wire firstWire = new Wire();
        Wire secondWire = new Wire();
        DisplayEntityGroup first = group(governor, firstWire);
        DisplayEntityGroup second = group(governor, secondWire);
        first.batch(() -> {
            first.show(UUID.randomUUID());
            first.show(UUID.randomUUID());
        });
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        second.batch(() -> {
            second.show(a);
            second.show(b);
        });
        assertTrue(first.visible());
        assertFalse(second.visible());
        assertTrue(secondWire.visible.isEmpty());
        assertEquals(2, governor.snapshot().visibleEntities());
        first.close();
        second.refresh();
        assertEquals(Set.of(a, b), secondWire.visible);
        assertEquals(2, governor.snapshot().visibleEntities());
        second.close();
        assertEquals(0, governor.snapshot().visibleEntities());
    }

    @Test
    void deletionFailureKeepsCapacityReservedUntilTheRetrySucceeds() {
        BudgetedVisibilityGovernor governor = governor(2);
        Wire wire = new Wire();
        DisplayEntityGroup group = group(governor, wire);
        group.batch(() -> {
            group.show(UUID.randomUUID());
            group.show(UUID.randomUUID());
        });
        wire.failRemove = true;
        assertThrows(IllegalStateException.class, group::close);
        assertEquals(2, governor.snapshot().visibleEntities());
        Wire waiting = new Wire();
        DisplayEntityGroup other = group(governor, waiting);
        other.show(UUID.randomUUID());
        assertFalse(other.visible());
        wire.failRemove = false;
        group.refresh();
        assertEquals(0, governor.snapshot().visibleEntities());
        other.refresh();
        assertTrue(other.visible());
    }

    @Test
    void distanceCullingRemovesEntitiesBeforeReturningTheirReservation() {
        BudgetedVisibilityGovernor governor = governor(3);
        Wire wire = new Wire();
        DisplayEntityGroup group = group(governor, wire);
        UUID handle = UUID.randomUUID();
        group.show(handle);
        group.culled(true);
        group.refresh();
        assertEquals(0, governor.snapshot().visibleEntities());
        assertTrue(wire.visible.isEmpty());
        group.culled(false);
        group.refresh();
        assertEquals(Set.of(handle), wire.visible);
        group.disconnected();
        assertEquals(0, governor.snapshot().visibleEntities());
    }

    @Test
    void partialSpawnFailureReservesTheWholeGroupUntilEveryPossibleEntityIsRemoved() {
        BudgetedVisibilityGovernor governor = governor(3);
        Wire wire = new Wire();
        wire.failSpawnAfter = 1;
        DisplayEntityGroup group = group(governor, wire);
        assertThrows(IllegalStateException.class, () -> group.batch(() -> {
            group.show(UUID.randomUUID());
            group.show(UUID.randomUUID());
        }));
        assertEquals(2, governor.snapshot().visibleEntities());
        assertEquals(1, wire.visible.size());
        group.close();
        assertTrue(wire.visible.isEmpty());
        assertEquals(0, governor.snapshot().visibleEntities());
    }

    @Test
    void visibilityObserversFollowAdmissionCullingAndRestorationWithoutDuplicateEvents() {
        BudgetedVisibilityGovernor governor = governor(1);
        DisplayEntityGroup group = group(governor, new Wire());
        List<Boolean> changes = new ArrayList<>();
        group.visibilityObserver(changes::add);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        group.show(first);
        group.refresh();
        assertEquals(List.of(true), changes);
        group.show(second);
        assertEquals(List.of(true, false), changes);
        group.hide(second, true);
        assertEquals(List.of(true, false, true), changes);
        group.culled(true);
        group.refresh();
        group.culled(false);
        group.refresh();
        group.disconnected();
        group.disconnected();
        assertEquals(List.of(true, false, true, false, true, false), changes);
    }

    @Test
    void rejectedGrowthRemovesTheOldGroupBeforeAnotherGroupCanUseItsCapacity() {
        BudgetedVisibilityGovernor governor = governor(2);
        Wire wire = new Wire();
        DisplayEntityGroup group = group(governor, wire);
        group.show(UUID.randomUUID());
        group.batch(() -> {
            group.show(UUID.randomUUID());
            group.show(UUID.randomUUID());
        });
        assertFalse(group.visible());
        assertTrue(wire.visible.isEmpty());
        assertEquals(0, governor.snapshot().visibleEntities());
    }

    private static DisplayEntityGroup group(BudgetedVisibilityGovernor governor, Wire wire) {
        UUID id = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getUniqueId")) {
                    return id;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        return new DisplayEntityGroup(new DisplayEntityGroup.Options(player, VisibilityGovernor.Surface.MENU,
            () -> governor, wire));
    }

    private static BudgetedVisibilityGovernor governor(int maximum) {
        BudgetedVisibilityGovernor.Policy policy = new BudgetedVisibilityGovernor.Policy(maximum, maximum, 0, 0, 10, 20, 30);
        return new BudgetedVisibilityGovernor(() -> new BudgetedVisibilityGovernor.Limits(maximum, maximum, policy, Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
    }

    private static final class Wire implements DisplayEntityGroup.Transport {
        private final Set<UUID> visible = new HashSet<>();
        private boolean failRemove;
        private int failSpawnAfter = Integer.MAX_VALUE;

        @Override
        public boolean spawn(UUID handle) {
            if (visible.size() >= failSpawnAfter) {
                throw new IllegalStateException("channel rejected spawn");
            }
            visible.add(handle);
            return true;
        }

        @Override
        public void remove(List<UUID> handles, boolean delete) {
            if (failRemove) {
                throw new IllegalStateException("channel rejected removal");
            }
            visible.removeAll(handles);
        }
    }
}
