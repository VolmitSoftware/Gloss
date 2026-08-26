package art.arcane.gloss.indicator;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageIndicatorConditionPlanTest {
    private static final BoundedConditionErrorCallback SILENT =
        BoundedConditionErrorCallback.silent();

    @Test
    void falseEventGateSuppressesTheEntireStyle() {
        DamageIndicatorSettingsDoc document = document(
            new DamageIndicatorSettingsDoc.Style(
                "subject.health < 5", presentation("base"), List.of()),
            new DamageIndicatorSettingsDoc.Audience("true"));

        DamageIndicatorSettingsDoc.IndicatorPresentation selected =
            DamageIndicatorConditionPlan.compile(document).select(
                true, scope(Map.of("subject.health", 5.0D)), SILENT);

        assertNull(selected);
    }

    @Test
    void variantsUsePriorityThenStableIdBeforeFallingBackToBase() {
        DamageIndicatorSettingsDoc.Style style = new DamageIndicatorSettingsDoc.Style(
            "true",
            presentation("base"),
            List.of(
                new DamageIndicatorSettingsDoc.Variant(
                    "zeta", 20, "event.amount >= 5", presentation("zeta")),
                new DamageIndicatorSettingsDoc.Variant(
                    "alpha", 20, "event.amount >= 5", presentation("alpha")),
                new DamageIndicatorSettingsDoc.Variant(
                    "higher", 30, "event.amount >= 10", presentation("higher"))));
        DamageIndicatorConditionPlan plan = DamageIndicatorConditionPlan.compile(
            document(style, new DamageIndicatorSettingsDoc.Audience("true")));

        assertEquals("higher{amount}", plan.select(
            true, scope(Map.of("event.amount", 12.0D)), SILENT).format());
        assertEquals("alpha{amount}", plan.select(
            true, scope(Map.of("event.amount", 7.0D)), SILENT).format());
        assertEquals("base{amount}", plan.select(
            true, scope(Map.of("event.amount", 2.0D)), SILENT).format());
    }

    @Test
    void criticalVariantRequiresKnownCriticality() {
        DamageIndicatorSettingsDoc.Style style = new DamageIndicatorSettingsDoc.Style(
            "true",
            presentation("base"),
            List.of(new DamageIndicatorSettingsDoc.Variant(
                "critical", 100, "event.criticalKnown && event.critical", presentation("critical"))));
        DamageIndicatorConditionPlan plan = DamageIndicatorConditionPlan.compile(
            document(style, new DamageIndicatorSettingsDoc.Audience("true")));

        assertEquals("critical{amount}", plan.select(true, scope(Map.of(
            "event.criticalKnown", true,
            "event.critical", true)), SILENT).format());
        assertEquals("base{amount}", plan.select(true, scope(Map.of(
            "event.criticalKnown", false,
            "event.critical", false)), SILENT).format());
    }

    @Test
    void audienceConditionMakesAnIndependentPerViewerDecision() {
        DamageIndicatorConditionPlan plan = DamageIndicatorConditionPlan.compile(document(
            new DamageIndicatorSettingsDoc.Style("true", presentation("base"), List.of()),
            new DamageIndicatorSettingsDoc.Audience(
                "viewer.world == subject.world && viewer.health > 0")));

        assertTrue(plan.includesViewer(scope(Map.of(
            "viewer.world", "arena",
            "subject.world", "arena",
            "viewer.health", 10.0D)), SILENT));
        assertFalse(plan.includesViewer(scope(Map.of(
            "viewer.world", "lobby",
            "subject.world", "arena",
            "viewer.health", 10.0D)), SILENT));
    }

    private static DamageIndicatorSettingsDoc document(
        DamageIndicatorSettingsDoc.Style damage, DamageIndicatorSettingsDoc.Audience audience) {
        return new DamageIndicatorSettingsDoc(
            DamageIndicatorSettingsDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION,
            null,
            damage,
            null,
            audience);
    }

    private static DamageIndicatorSettingsDoc.IndicatorPresentation presentation(String name) {
        return new DamageIndicatorSettingsDoc.IndicatorPresentation(
            name + "{amount}",
            new Vector(),
            new DamageIndicatorSettingsDoc.Motion(0.0D, 0.0D, 0.0D, 0.0D),
            new DamageIndicatorSettingsDoc.Transform(1.0D, 1.0D, 1.0D), List.of());
    }

    private static ExprScope scope(Map<String, Object> values) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return values.get(dottedName);
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return ExprFunctions.call(name, arguments);
            }
        };
    }
}
