package art.arcane.gloss.indicator;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class DamageIndicatorConditionPlan {
    private static final Comparator<CompiledVariant> VARIANT_ORDER = Comparator
        .comparingInt(CompiledVariant::priority)
        .reversed()
        .thenComparing(CompiledVariant::id);

    private final CompiledStyle damage;
    private final CompiledStyle healing;
    private final CompiledCondition audience;

    private DamageIndicatorConditionPlan(CompiledStyle damage, CompiledStyle healing,
                                         CompiledCondition audience) {
        this.damage = damage;
        this.healing = healing;
        this.audience = audience;
    }

    static DamageIndicatorConditionPlan compile(DamageIndicatorSettingsDoc document) {
        return new DamageIndicatorConditionPlan(
            compileStyle("damage", document.damage()),
            compileStyle("healing", document.healing()),
            compile("audience.when", document.audience().when()));
    }

    DamageIndicatorSettingsDoc.IndicatorPresentation select(
        boolean damageEvent, ExprScope scope, BoundedConditionErrorCallback errors) {
        return (damageEvent ? damage : healing).select(scope, errors);
    }

    boolean includesViewer(ExprScope scope, BoundedConditionErrorCallback errors) {
        return audience.matches(scope, errors);
    }

    private static CompiledStyle compileStyle(String name, DamageIndicatorSettingsDoc.Style style) {
        List<CompiledVariant> variants = new ArrayList<CompiledVariant>(style.variants().size());
        for (DamageIndicatorSettingsDoc.Variant variant : style.variants()) {
            variants.add(new CompiledVariant(
                variant.id(),
                variant.priority(),
                compile(name + ".variants." + variant.id() + ".when", variant.when()),
                variant.presentation()));
        }
        variants.sort(VARIANT_ORDER);
        return new CompiledStyle(
            compile(name + ".when", style.when()),
            style.presentation(),
            List.copyOf(variants));
    }

    private static CompiledCondition compile(String path, String expression) {
        return ConditionCompiler.compile(new ConditionSource(
            DamageIndicatorSettingsDoc.KIND + "/default.json." + path, expression));
    }

    private record CompiledStyle(CompiledCondition when,
                                 DamageIndicatorSettingsDoc.IndicatorPresentation presentation,
                                 List<CompiledVariant> variants) {
        private DamageIndicatorSettingsDoc.IndicatorPresentation select(
            ExprScope scope, BoundedConditionErrorCallback errors) {
            if (!when.matches(scope, errors)) {
                return null;
            }
            for (CompiledVariant variant : variants) {
                if (variant.when().matches(scope, errors)) {
                    return variant.presentation();
                }
            }
            return presentation;
        }
    }

    private record CompiledVariant(String id, int priority, CompiledCondition when,
                                   DamageIndicatorSettingsDoc.IndicatorPresentation presentation) {
    }
}
