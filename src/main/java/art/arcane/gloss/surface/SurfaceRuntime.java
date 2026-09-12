package art.arcane.gloss.surface;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.hud.HudSlot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class SurfaceRuntime {
    private final String id;
    private final SurfaceDoc doc;
    private final CompiledCondition selectWhen;
    private final SurfaceProfile baseProfile;
    private final List<CompiledVariant> variants;

    private SurfaceRuntime(String id, SurfaceDoc doc, CompiledCondition selectWhen, SurfaceProfile baseProfile,
                           List<CompiledVariant> variants) {
        this.id = id;
        this.doc = doc;
        this.selectWhen = selectWhen;
        this.baseProfile = baseProfile;
        this.variants = variants;
    }

    public static SurfaceRuntime compile(String id, SurfaceDoc doc) {
        CompiledCondition selectWhen = ConditionCompiler.compile(
            new ConditionSource("surfaces." + id + ".select.when", doc.select().when()));
        List<CompiledVariant> compiled = new ArrayList<>(doc.variants().size());
        for (SurfaceDoc.Variant variant : doc.variants()) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "surfaces." + id + ".variants." + variant.id() + ".when", variant.when()));
            compiled.add(new CompiledVariant(variant, condition,
                profile(id, variant.id(), variant.presentation())));
        }
        compiled.sort(Comparator
            .comparingInt((CompiledVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));
        return new SurfaceRuntime(id, doc, selectWhen, profile(id, "base", doc.presentation()),
            List.copyOf(compiled));
    }

    public static Optional<SurfaceRuntime> pick(List<SurfaceRuntime> candidates, ExprScope scope,
                                                BoundedConditionErrorCallback errors) {
        SurfaceRuntime selected = null;
        for (SurfaceRuntime candidate : candidates) {
            if (!candidate.selected(scope, errors)) {
                continue;
            }
            if (selected == null || candidate.selectionPriority() > selected.selectionPriority()
                || candidate.selectionPriority() == selected.selectionPriority()
                && candidate.id().compareTo(selected.id()) < 0) {
                selected = candidate;
            }
        }
        return Optional.ofNullable(selected);
    }

    public String id() {
        return id;
    }

    public SurfaceKind kind() {
        return doc.surface();
    }

    public SurfaceDoc doc() {
        return doc;
    }

    public int selectionPriority() {
        return doc.select().priority();
    }

    public boolean selected(ExprScope scope, BoundedConditionErrorCallback errors) {
        return doc.show().matches(scope, errors) && selectWhen.matches(scope, errors);
    }

    public SurfaceProfile profile(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (CompiledVariant candidate : variants) {
            if (candidate.condition().matches(scope, errors)) {
                return candidate.profile();
            }
        }
        return baseProfile;
    }

    private static SurfaceProfile profile(String surfaceId, String profileId, SurfaceDoc.Presentation presentation) {
        Expr progress = presentation.progress() == null ? null : ExprParser.parse(presentation.progress());
        return new SurfaceProfile(profileId, presentation, progress, presentation.priorityValue(),
            presentation.hudSlots(), "surfaces." + surfaceId + "." + profileId + ".progress");
    }

    public record SurfaceProfile(String id, SurfaceDoc.Presentation presentation, Expr progressExpression,
                                 int priorityValue, List<HudSlot> slots, String progressPath) {
        public double progress(ExprScope scope) {
            if (progressExpression == null) {
                return 1.0D;
            }
            try {
                return Math.clamp(ExprEvaluator.number(progressExpression, scope), 0.0D, 1.0D);
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "surface-progress-" + progressPath, failure,
                    "Surface progress %s failed and was treated as 0.", progressPath);
                return 0.0D;
            }
        }
    }

    private record CompiledVariant(SurfaceDoc.Variant variant, CompiledCondition condition, SurfaceProfile profile) {
    }
}
