package art.arcane.gloss.drop;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealDropConditionPlanTest {

    @Test
    void highestPriorityMatchingStyleWins() {
        RealDropConditionPlan plan = plan(List.of(
            variant("low", 10, "drop.amount >= 2"),
            variant("high", 20, "drop.material == 'DIAMOND'")));

        assertEquals("high", plan.select(scope(Map.of(
            "drop.amount", 64.0D,
            "drop.material", "DIAMOND"))).id());
    }

    @Test
    void lexicographicallySmallestIdBreaksAPriorityTie() {
        RealDropConditionPlan plan = plan(List.of(
            variant("zeta", 50, "true"),
            variant("alpha", 50, "true")));

        assertEquals("alpha", plan.select(scope(Map.of())).id());
    }

    @Test
    void fallbackStyleWinsWhenNoVariantMatches() {
        RealDropConditionPlan plan = plan(List.of(
            variant("nether", 10, "drop.world == 'world_nether'")));

        assertEquals("base", plan.select(scope(Map.of("drop.world", "world"))).id());
    }

    @Test
    void invalidAndDuplicateVariantContractsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> plan(List.of(variant("broken", 1, "drop.amount >"))));
        assertThrows(IllegalArgumentException.class, () -> new RealDropSettingsDoc(
            RealDropSettingsDoc.CURRENT_SCHEMA_VERSION,
            1L,
            RealDropSettingsDoc.DEFAULTS.presentation(),
            List.of(variant("same", 1, "true"), variant("same", 2, "false")),
            new RealDropSettingsDoc.Audience("true"), null));
    }

    private static RealDropConditionPlan plan(List<RealDropSettingsDoc.Variant> variants) {
        RealDropSettingsDoc document = new RealDropSettingsDoc(
            RealDropSettingsDoc.CURRENT_SCHEMA_VERSION,
            1L,
            RealDropSettingsDoc.DEFAULTS.presentation(),
            variants,
            new RealDropSettingsDoc.Audience("true"), null);
        return RealDropConditionPlan.compile(
            document, true, BoundedConditionErrorCallback.silent());
    }

    private static RealDropSettingsDoc.Variant variant(String id, int priority, String when) {
        return new RealDropSettingsDoc.Variant(
            id, priority, when, RealDropSettingsDoc.DEFAULTS.presentation());
    }

    private static ExprScope scope(Map<String, Object> values) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return values.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }
        };
    }
}
