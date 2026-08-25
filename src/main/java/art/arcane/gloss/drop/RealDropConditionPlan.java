package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class RealDropConditionPlan {

    private static final String BASE_ID = "base";

    private final ResolvedStyle base;
    private final List<ConditionalStyle> variants;
    private final CompiledCondition audience;
    private final boolean universalAudience;
    private final boolean emptyAudience;
    private final BoundedConditionErrorCallback errors;

    private RealDropConditionPlan(ResolvedStyle base, List<ConditionalStyle> variants,
                                  CompiledCondition audience, BoundedConditionErrorCallback errors) {
        this.base = base;
        this.variants = variants;
        this.audience = audience;
        this.universalAudience = audience.source().expression().equals("true");
        this.emptyAudience = audience.source().expression().equals("false");
        this.errors = errors;
    }

    static RealDropConditionPlan compile(RealDropSettingsDoc document, boolean enabled,
                                         BoundedConditionErrorCallback errors) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(errors);
        ResolvedStyle base = resolve(BASE_ID, document.presentation(), enabled);
        List<ConditionalStyle> variants = new ArrayList<>(document.variants().size());
        for (RealDropSettingsDoc.Variant variant : document.variants()) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "real-drops/default.json $.variants[" + variant.id() + "].when", variant.when()));
            variants.add(new ConditionalStyle(
                variant.id(), variant.priority(), condition,
                resolve(variant.id(), variant.presentation(), enabled)));
        }
        variants.sort(Comparator.comparingInt(ConditionalStyle::priority).reversed()
            .thenComparing(ConditionalStyle::id));
        CompiledCondition audience = ConditionCompiler.compile(new ConditionSource(
            "real-drops/default.json $.audience.when", document.audience().when()));
        return new RealDropConditionPlan(base, List.copyOf(variants), audience, errors);
    }

    Selection select(Gloss plugin, Item item, RealDropConditionSnapshot snapshot) {
        ExprScope scope = new GlossConditionScope(plugin, snapshot.itemContext(item));
        return new Selection(select(scope), audience, universalAudience, emptyAudience, snapshot, errors);
    }

    ResolvedStyle select(ExprScope scope) {
        for (ConditionalStyle variant : variants) {
            if (variant.when().matches(scope, errors)) {
                return variant.style();
            }
        }
        return base;
    }

    private static ResolvedStyle resolve(String id, RealDropSettingsDoc.Presentation presentation,
                                         boolean enabled) {
        GlossConfig.RealDrops config = presentation.toConfig(enabled);
        RealDropScriptPlan script = config.script().enabled()
            ? RealDropScriptPlan.compile(config.script())
            : null;
        RealDropAnimationPlan animation = RealDropAnimationPlan.compile(config.animation());
        return new ResolvedStyle(id, config, script, animation);
    }

    record Selection(ResolvedStyle style, CompiledCondition audience, boolean universalAudience,
                     boolean emptyAudience, RealDropConditionSnapshot snapshot,
                     BoundedConditionErrorCallback errors) {

        Selection {
            Objects.requireNonNull(style);
            Objects.requireNonNull(audience);
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(errors);
        }

        boolean visibleTo(Gloss plugin, Player viewer) {
            if (universalAudience) {
                return true;
            }
            if (emptyAudience) {
                return false;
            }
            return audience.matches(
                new GlossConditionScope(plugin, snapshot.viewerContext(viewer)), errors);
        }
    }

    record ResolvedStyle(String id, GlossConfig.RealDrops config, RealDropScriptPlan script,
                         RealDropAnimationPlan animation) {
    }

    private record ConditionalStyle(String id, int priority, CompiledCondition when,
                                    ResolvedStyle style) {
    }
}
