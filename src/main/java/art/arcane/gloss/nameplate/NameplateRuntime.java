package art.arcane.gloss.nameplate;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.text.TextPipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One nameplate document with its conditions compiled. Selection follows the board model exactly:
 * highest priority wins, ties break on the lowest id, and a variant replaces the presentation
 * instead of merging with it.
 */
public final class NameplateRuntime {
    private final String id;
    private final NameplateDoc doc;
    private final CompiledCondition selection;
    private final Map<String, CompiledCondition> variantConditions = new LinkedHashMap<>();
    private final boolean viewerDependent;

    public NameplateRuntime(String id, NameplateDoc doc) {
        this.id = Objects.requireNonNull(id, "id");
        this.doc = Objects.requireNonNull(doc, "doc");
        this.selection = ConditionCompiler.compile(
            new ConditionSource("nameplates." + id + ".select.when", doc.select().when()));
        for (NameplateDoc.Variant variant : doc.variants()) {
            variantConditions.put(variant.id(), ConditionCompiler.compile(
                new ConditionSource("nameplates." + id + ".variants." + variant.id(), variant.when())));
        }
        this.viewerDependent = computeViewerDependent(doc);
    }

    /** @return the highest-priority visible runtime, or null when none matches */
    public static NameplateRuntime select(List<NameplateRuntime> runtimes, ExprScope scope) {
        NameplateRuntime selected = null;
        for (NameplateRuntime runtime : runtimes) {
            if (!runtime.matches(scope)) {
                continue;
            }
            if (selected == null || runtime.priority() > selected.priority()
                || runtime.priority() == selected.priority() && runtime.id().compareTo(selected.id()) < 0) {
                selected = runtime;
            }
        }
        return selected;
    }

    public String id() {
        return id;
    }

    public NameplateDoc doc() {
        return doc;
    }

    public int priority() {
        return doc.select().priority();
    }

    /** True when the pane's text reads anything about the viewer, so it cannot be shared. */
    public boolean viewerDependent() {
        return viewerDependent;
    }

    public boolean matches(ExprScope scope) {
        return doc.show().matches(scope)
            && selection.matches(scope, BoundedConditionErrorCallback.silent());
    }

    public NameplateDoc.Presentation presentation(ExprScope scope) {
        NameplateDoc.Variant chosen = null;
        for (NameplateDoc.Variant variant : doc.variants()) {
            if (!variantConditions.get(variant.id()).matches(scope, BoundedConditionErrorCallback.silent())) {
                continue;
            }
            if (chosen == null || variant.priority() > chosen.priority()
                || variant.priority() == chosen.priority() && variant.id().compareTo(chosen.id()) < 0) {
                chosen = variant;
            }
        }
        return chosen == null ? doc.presentation() : chosen.presentation();
    }

    public List<String> visibleLines(ExprScope selectionScope, ExprScope lineScope) {
        List<String> texts = new ArrayList<>();
        for (NameplateDoc.Line line : presentation(selectionScope).lines()) {
            if (line.show().matches(lineScope)) {
                texts.add(line.text());
            }
        }
        return List.copyOf(texts);
    }

    /** The first relation whose condition holds, or an empty string when none does. */
    public String relationColor(NameplateDoc.Presentation presentation, ExprScope scope) {
        for (NameplateDoc.Relation relation : presentation.relations()) {
            if (relation.when().matches(scope)) {
                return relation.color();
            }
        }
        return "";
    }

    private static boolean computeViewerDependent(NameplateDoc doc) {
        if (viewerDependent(doc.presentation())) {
            return true;
        }
        for (NameplateDoc.Variant variant : doc.variants()) {
            if (viewerDependent(variant.presentation())) {
                return true;
            }
        }
        return false;
    }

    private static boolean viewerDependent(NameplateDoc.Presentation presentation) {
        for (NameplateDoc.Line line : presentation.lines()) {
            if (TextPipeline.viewerSpecific(line.text()) || line.text().contains("viewer.")
                || line.show().expression().contains("viewer.")) {
                return true;
            }
        }
        for (NameplateDoc.Relation relation : presentation.relations()) {
            if (relation.when().expression().contains("viewer.")) {
                return true;
            }
        }
        return false;
    }
}
