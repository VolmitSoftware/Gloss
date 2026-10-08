package art.arcane.gloss.hologram;

import art.arcane.gloss.service.BudgetedVisibilityGovernor;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.service.AdmissionBudget;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerDisplayBudgetTest {
    @TempDir File directory;

    @Test
    void worldEntitiesConsumeCapacityForEachReceivingViewerAndRetryAfterHide() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle first = harness.join("First", world, 0, 64, 3);
            CharacterizationHarness.PlayerHandle second = harness.join("Second", world, 1, 64, 3);
            BudgetedVisibilityGovernor.Policy policy = new BudgetedVisibilityGovernor.Policy(2, 2, 0, 0, 10, 20, 30);
            BudgetedVisibilityGovernor governor = new BudgetedVisibilityGovernor(
                () -> new BudgetedVisibilityGovernor.Limits(2, 2, policy, Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
            harness.governor(governor);
            TextDisplay a = world.proxy.spawn(harness.at(world, 0, 64, 0), TextDisplay.class);
            TextDisplay b = world.proxy.spawn(harness.at(world, 0, 65, 0), TextDisplay.class);
            ViewerDisplayBudget group = new ViewerDisplayBudget(harness.gloss, () -> VisibilityGovernor.Surface.HOLOGRAM);
            List<String> visibility = new ArrayList<>();
            group.visibilityObserver((id, shown) -> visibility.add(id + ":" + shown));
            assertTrue(group.show(first.proxy, List.of(a, b)));
            assertEquals(2, governor.snapshot().visibleEntities());
            assertFalse(group.show(second.proxy, List.of(a, b)));
            assertEquals(List.of(first.uuid + ":true"), visibility);
            assertTrue(group.show(first.proxy, List.of(a, b)));
            assertEquals(1, visibility.size());
            group.hide(first.proxy);
            assertEquals(0, governor.snapshot().visibleEntities());
            assertTrue(group.show(second.proxy, List.of(a, b)));
            group.forget(second.uuid);
            assertEquals(0, governor.snapshot().visibleEntities());
            assertEquals(List.of(first.uuid + ":true", first.uuid + ":false",
                second.uuid + ":true", second.uuid + ":false"), visibility);
        }
    }

    @Test
    void rejectedOwnerTeardownRetainsTheReservationUntilItCanRun() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 3);
            BudgetedVisibilityGovernor.Policy policy = new BudgetedVisibilityGovernor.Policy(1, 1, 0, 0, 10, 20, 30);
            BudgetedVisibilityGovernor governor = new BudgetedVisibilityGovernor(
                () -> new BudgetedVisibilityGovernor.Limits(1, 1, policy, Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
            harness.governor(governor);
            TextDisplay display = world.proxy.spawn(harness.at(world, 0, 64, 0), TextDisplay.class);
            ViewerDisplayBudget group = new ViewerDisplayBudget(harness.gloss, () -> VisibilityGovernor.Surface.HOLOGRAM);
            List<Boolean> visibility = new ArrayList<>();
            group.visibilityObserver((id, shown) -> visibility.add(shown));
            assertTrue(group.show(viewer.proxy, List.of(display)));
            int pending = DisplayEntityManager.pendingRetirements();
            harness.enabled(false);
            group.clear();
            assertEquals(1, governor.snapshot().visibleEntities());
            assertEquals(pending + 1, DisplayEntityManager.pendingRetirements());
            assertEquals(List.of(true), visibility);
            harness.enabled(true);
            DisplayEntityManager.pumpRetirements();
            assertEquals(0, governor.snapshot().visibleEntities());
            assertEquals(pending, DisplayEntityManager.pendingRetirements());
            assertEquals(List.of(true, false), visibility);
        }
    }

    @Test
    void existingViewerReconcilesMembershipAndReplacementGovernor() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 3);
            BudgetedVisibilityGovernor.Policy policy = new BudgetedVisibilityGovernor.Policy(2, 2, 0, 0, 10, 20, 30);
            BudgetedVisibilityGovernor first = new BudgetedVisibilityGovernor(
                () -> new BudgetedVisibilityGovernor.Limits(2, 2, policy, Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
            harness.governor(first);
            TextDisplay a = world.proxy.spawn(harness.at(world, 0, 64, 0), TextDisplay.class);
            TextDisplay b = world.proxy.spawn(harness.at(world, 0, 65, 0), TextDisplay.class);
            ViewerDisplayBudget group = new ViewerDisplayBudget(harness.gloss, () -> VisibilityGovernor.Surface.HOLOGRAM);
            assertTrue(group.show(viewer.proxy, List.of(a, b)));
            assertTrue(group.show(viewer.proxy, List.of(b)));
            assertEquals(1, first.snapshot().visibleEntities());

            BudgetedVisibilityGovernor replacement = new BudgetedVisibilityGovernor(
                () -> new BudgetedVisibilityGovernor.Limits(1, 1, policy, Map.of(), new BudgetedVisibilityGovernor.AdmissionPolicy(BudgetedVisibilityGovernor.AdmissionMode.REJECT, 4096, 100)));
            harness.governor(replacement);
            assertFalse(group.show(viewer.proxy, List.of(a, b)));
            assertEquals(0, first.snapshot().visibleEntities());
            assertEquals(0, replacement.snapshot().visibleEntities());
            assertTrue(group.show(viewer.proxy, List.of(b)));
            assertEquals(1, replacement.snapshot().visibleEntities());
            group.close();
            assertFalse(group.show(viewer.proxy, List.of(a)));
            assertEquals(0, replacement.snapshot().visibleEntities());
        }
    }

    @Test
    void unchangedSingletonRenewsAfterRefusalAndViewerReconnect() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 3);
            TextDisplay display = world.proxy.spawn(harness.at(world, 0, 64, 0), TextDisplay.class);
            AdmissionBudget budget = new AdmissionBudget(1);
            AtomicBoolean allowed = new AtomicBoolean(true);
            AtomicInteger renewals = new AtomicInteger();
            harness.governor(new VisibilityGovernor() {
                @Override
                public AdmissionBudget.Lease admit(Player player, Surface surface, int entities) {
                    return allowed.get() ? budget.tryAcquire() : null;
                }

                @Override
                public AdmissionBudget.Lease renew(AdmissionBudget.Lease previous, Player player, Surface surface, int entities) {
                    renewals.incrementAndGet();
                    return VisibilityGovernor.super.renew(previous, player, surface, entities);
                }

                @Override
                public Tier tier(Player player, Surface surface, double distanceSquared) {
                    return Tier.FULL;
                }
            });
            ViewerDisplayBudget group = new ViewerDisplayBudget(harness.gloss, () -> VisibilityGovernor.Surface.HOLOGRAM);
            List<Boolean> visibility = new ArrayList<>();
            group.visibilityObserver((id, shown) -> visibility.add(shown));
            assertTrue(group.show(viewer.proxy, List.of(display)));
            allowed.set(false);
            assertFalse(group.show(viewer.proxy, List.of(display)));
            assertEquals(0, budget.active());
            allowed.set(true);
            assertTrue(group.show(viewer.proxy, List.of(display)));
            assertTrue(group.show(viewer.proxy, List.of(display)));
            assertEquals(4, renewals.get());
            assertEquals(1, budget.active());
            assertEquals(List.of(true, false, true), visibility);

            group.forget(viewer.uuid);
            assertEquals(0, budget.active());
            harness.quit(viewer);
            CharacterizationHarness.PlayerHandle returning = harness.join("Viewer", viewer.uuid, world, 0, 64, 3);
            assertTrue(group.show(returning.proxy, List.of(display)));
            assertEquals(5, renewals.get());
            assertEquals(1, budget.active());
            assertEquals(1, returning.showCalls.get(display.getUniqueId()));
            group.close();
            assertFalse(group.show(returning.proxy, List.of(display)));
            assertEquals(0, budget.active());
        }
    }

    @Test
    void aReplacementWrapperWithTheSameUuidUpdatesTheRemovalTransport() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 3);
            TextDisplay display = world.proxy.spawn(harness.at(world, 0, 64, 0), TextDisplay.class);
            TextDisplay replacement = (TextDisplay) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TextDisplay.class}, (proxy, method, arguments) -> method.invoke(display, arguments));
            List<Entity> hidden = new ArrayList<>();
            Player receiving = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("hideEntity")) {
                        hidden.add((Entity) arguments[1]);
                    }
                    return method.invoke(viewer.proxy, arguments);
                });
            ViewerDisplayBudget group = new ViewerDisplayBudget(harness.gloss, () -> VisibilityGovernor.Surface.HOLOGRAM);

            assertTrue(group.show(receiving, List.of(display)));
            assertTrue(group.show(receiving, List.of(replacement)));
            group.hide(receiving);

            assertEquals(1, hidden.size());
            assertSame(replacement, hidden.getFirst());
        }
    }

    @Test
    void aVisibilityObserverCanReplaceTheMembershipDuringPublication() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            CharacterizationHarness.WorldState world = harness.world("world");
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 3);
            TextDisplay first = world.proxy.spawn(harness.at(world, 0, 64, 0), TextDisplay.class);
            TextDisplay replacement = world.proxy.spawn(harness.at(world, 0, 65, 0), TextDisplay.class);
            ViewerDisplayBudget group = new ViewerDisplayBudget(harness.gloss, () -> VisibilityGovernor.Surface.HOLOGRAM);
            AtomicInteger publications = new AtomicInteger();
            group.visibilityObserver((id, shown) -> {
                if (shown && publications.incrementAndGet() == 1) {
                    assertTrue(group.show(viewer.proxy, List.of(replacement)));
                }
            });

            assertTrue(group.show(viewer.proxy, List.of(first)));
            assertEquals(1, publications.get());
            assertEquals(false, viewer.perceived.get(first.getUniqueId()));
            assertEquals(true, viewer.perceived.get(replacement.getUniqueId()));
            assertTrue(group.show(viewer.proxy, List.of(replacement)));
            assertEquals(1, publications.get());
        }
    }

}
