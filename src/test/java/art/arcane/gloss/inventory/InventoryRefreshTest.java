package art.arcane.gloss.inventory;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.RepeatScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryRefreshTest {
    @Test
    void staticWindowsStayIdleAndDynamicTitleSlotsAndConditionsRegisterIndependently() {
        InventoryRuntime plain = runtime("""
            {"schemaVersion":1,"revision":1,"title":"Shop","resolution":"9x1","mask":["........."]}
            """);
        assertFalse(plain.refresh().active());
        InventoryRuntime dynamic = runtime("""
            {"schemaVersion":1,"revision":1,"title":"{{ viewer.health }}","resolution":"9x1","mask":["A........"],
             "keys":{"A":{"type":"decoration","icon":{"type":"text","text":"{{ time.seconds }}"}}},
             "show":"viewer.health > 0","refresh":{"titleTicks":3,"slotsTicks":7,"conditionsTicks":11}}
            """);
        assertEquals(new InventoryRefreshPlan(3, 7, 11, 0), dynamic.refresh());
    }

    @Test
    void constantListUsesLegacyCadenceOnlyWhenItsTemplateChanges() {
        InventoryRuntime runtime = runtime("""
            {"schemaVersion":1,"revision":1,"resolution":"9x1","mask":["........."],
             "list":{"area":".","var":"entry","source":"[1,2]","refreshTicks":37,
             "template":{"type":"decoration","icon":{"type":"text","text":"{{ time.seconds }}"}}}}
            """);
        assertEquals(37, runtime.refresh().listTicks());
    }

    @Test
    void independentClocksDoNotRestartUnrelatedCadencesAndZeroMeansManual() {
        InventoryRefreshClock clock = new InventoryRefreshClock(new InventoryRefreshPlan(3, 7, 0, 11), 100);
        assertEquals(0, clock.due(102));
        assertEquals(InventoryRefreshPlan.TITLE, clock.due(103));
        clock.rendered(InventoryRefreshPlan.TITLE, 103);
        assertEquals(InventoryRefreshPlan.TITLE | InventoryRefreshPlan.SLOTS, clock.due(107));
        clock.rendered(InventoryRefreshPlan.SLOTS, 107);
        assertEquals(InventoryRefreshPlan.TITLE | InventoryRefreshPlan.LIST, clock.due(111));
    }

    @Test
    void forcedRefreshAndPolicySurviveRevisionUpdates() {
        InventoryDoc doc = InventoryDoc.parse("test", """
            {"schemaVersion":1,"revision":1,"resolution":"9x1","mask":["........."],
             "refresh":{"mode":"always","titleTicks":0,"slotsTicks":4,"conditionsTicks":0}}
            """);
        assertEquals(doc.refresh(), doc.withRevision(2).refresh());
        assertEquals(new InventoryRefreshPlan(0, 4, 0, 0), InventoryRuntime.compile("test", doc).refresh());
        assertThrows(IllegalArgumentException.class, () -> new InventoryRefreshPolicy("other", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new InventoryRefreshPolicy(null, -1, null, null, null));
    }

    @Test
    void toggleCompilesBothIconsAndActionBranches() {
        InventoryRuntime runtime = runtime("""
            {"schemaVersion":1,"revision":1,"resolution":"9x1","mask":["A........"],
             "keys":{"A":{"type":"toggle","condition":"{{ session.enabled }}","expectedValue":"true",
               "trueIcon":{"type":"text","text":"On"},"falseIcon":{"type":"text","text":"Off"},
               "trueActions":[{"type":"message","message":"Enabled"}],
               "falseActions":[{"type":"message","message":"Disabled"}]}}}
            """);
        InventoryRuntime.Slot slot = runtime.present(scope()).slots().get(0);
        assertNotNull(slot.toggle());
        assertNotNull(slot.toggle().falseIcon());
        assertEquals(1, slot.actions().size());
        assertEquals(1, slot.toggle().falseActions().size());
        assertTrue(runtime.refresh().slotsTicks() > 0);
    }

    @Test
    void toggleStateSeparatesBaseAndListCellsAndResetsForDifferentPageEntries() {
        InventoryToggleState state = new InventoryToggleState();
        assertTrue(state.resolve(false, 0, "base", false, () -> true));
        assertFalse(state.resolve(true, 0, "first-entry", false, () -> false));
        state.flip(true, 0);
        assertTrue(state.resolve(true, 0, "first-entry", false, () -> false));
        assertFalse(state.resolve(true, 0, "second-entry", false, () -> false));
        assertTrue(state.current(false, 0));
        assertFalse(state.resolve(false, 0, "base", true, () -> false));
    }

    @Test
    void listClicksRetainTheRenderedEntryScope() {
        RepeatScope scope = new RepeatScope(scope(), "entry", "selected");
        InventoryActionContext context = new InventoryActionContext(null,
            new InventoryActionContext.Options("test", 0, HoloClickTrigger.ANY, Map.of(), null, scope, null, () -> true));
        assertEquals("selected", ExprEvaluator.eval(ExprParser.parse("entry"), context.conditionScope()));
    }

    private static InventoryRuntime runtime(String source) {
        return InventoryRuntime.compile("test", InventoryDoc.parse("test", source));
    }

    private static ExprScope scope() {
        return new ExprScope() {
            @Override
            public Object variable(String name) {
                return null;
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return null;
            }
        };
    }
}
