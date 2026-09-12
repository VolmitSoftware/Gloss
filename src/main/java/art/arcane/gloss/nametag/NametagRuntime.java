package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A compiled nametag document. {@link #viewerDependent()} decides how the service drives it: a
 * document that never reads {@code viewer.*} resolves once per subject, anything else resolves per
 * (viewer, subject) pair.
 */
public final class NametagRuntime {
    private static final String VIEWER_PREFIX = "viewer.";

    private final String id;
    private final NametagDoc doc;
    private final CompiledCondition selectWhen;
    private final List<CompiledVariant> variants;
    private final boolean viewerDependent;

    private NametagRuntime(String id, NametagDoc doc, CompiledCondition selectWhen,
                           List<CompiledVariant> variants, boolean viewerDependent) {
        this.id = id;
        this.doc = doc;
        this.selectWhen = selectWhen;
        this.variants = variants;
        this.viewerDependent = viewerDependent;
    }

    public static NametagRuntime compile(String id, NametagDoc doc) {
        CompiledCondition selectWhen = ConditionCompiler.compile(
            new ConditionSource("nametags." + id + ".select.when", doc.select().when()));
        List<CompiledVariant> compiled = new ArrayList<>(doc.variants().size());
        for (NametagDoc.Variant variant : doc.variants()) {
            compiled.add(new CompiledVariant(variant, ConditionCompiler.compile(new ConditionSource(
                "nametags." + id + ".variants." + variant.id() + ".when", variant.when()))));
        }
        compiled.sort(Comparator
            .comparingInt((CompiledVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));
        return new NametagRuntime(id, doc, selectWhen, List.copyOf(compiled), viewerDependent(doc, selectWhen,
            compiled));
    }

    public static Optional<NametagRuntime> pick(List<NametagRuntime> candidates, ExprScope scope,
                                                BoundedConditionErrorCallback errors) {
        NametagRuntime selected = null;
        for (NametagRuntime candidate : candidates) {
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

    public NametagDoc doc() {
        return doc;
    }

    public int selectionPriority() {
        return doc.select().priority();
    }

    public boolean viewerDependent() {
        return viewerDependent;
    }

    public boolean selected(ExprScope scope, BoundedConditionErrorCallback errors) {
        return doc.show().matches(scope, errors) && selectWhen.matches(scope, errors);
    }

    public Profile profile(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (CompiledVariant candidate : variants) {
            if (candidate.condition().matches(scope, errors)) {
                return new Profile(candidate.variant().id(), candidate.variant().presentation());
            }
        }
        return new Profile("base", doc.presentation());
    }

    private static boolean viewerDependent(NametagDoc doc, CompiledCondition selectWhen,
                                           List<CompiledVariant> variants) {
        if (readsViewer(doc.show().expression()) || readsViewer(selectWhen)) {
            return true;
        }
        if (readsViewer(doc.presentation())) {
            return true;
        }
        for (CompiledVariant variant : variants) {
            if (readsViewer(variant.condition()) || readsViewer(variant.variant().presentation())) {
                return true;
            }
        }
        return false;
    }

    private static boolean readsViewer(CompiledCondition condition) {
        for (String variable : condition.references().variables()) {
            if (variable.startsWith(VIEWER_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static boolean readsViewer(NametagDoc.Presentation presentation) {
        return readsViewer(presentation.prefix()) || readsViewer(presentation.suffix());
    }

    private static boolean readsViewer(String raw) {
        return raw != null && raw.contains(VIEWER_PREFIX);
    }

    public record Profile(String id, NametagDoc.Presentation presentation) {
    }

    private record CompiledVariant(NametagDoc.Variant variant, CompiledCondition condition) {
    }
}
