package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.text.TextPipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TablistRuntime {
    private static final String VIEWER_PREFIX = "viewer.";

    private final TablistDoc doc;
    private final List<HeaderFooterVariant> headerFooterVariants;
    private final List<ListNameVariant> listNameVariants;
    private final HeaderFooterProfile baseHeaderFooter;
    private final ListNameProfile baseListName;
    private final Expr sortWeight;
    private final boolean sortViewerDependent;

    private TablistRuntime(TablistDoc doc, List<HeaderFooterVariant> headerFooterVariants,
                           List<ListNameVariant> listNameVariants, Expr sortWeight, boolean sortViewerDependent) {
        this.doc = doc;
        this.headerFooterVariants = headerFooterVariants;
        this.listNameVariants = listNameVariants;
        this.baseHeaderFooter = new HeaderFooterProfile("base", doc.headerFooter().presentation());
        this.baseListName = new ListNameProfile("base", doc.listNames().presentation());
        this.sortWeight = sortWeight;
        this.sortViewerDependent = sortViewerDependent;
    }

    static TablistRuntime compile(TablistDoc doc) {
        List<HeaderFooterVariant> headerVariants = new ArrayList<>(doc.headerFooter().variants().size());
        for (TablistDoc.HeaderFooterVariant variant : doc.headerFooter().variants()) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "tablist.headerFooter.variants." + variant.id() + ".when", variant.when()));
            headerVariants.add(new HeaderFooterVariant(variant, condition,
                new HeaderFooterProfile(variant.id(), variant.presentation())));
        }
        headerVariants.sort(Comparator
            .comparingInt((HeaderFooterVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));

        List<ListNameVariant> nameVariants = new ArrayList<>(doc.listNames().variants().size());
        for (TablistDoc.ListNameVariant variant : doc.listNames().variants()) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "tablist.listNames.variants." + variant.id() + ".when", variant.when()));
            nameVariants.add(new ListNameVariant(variant, condition,
                new ListNameProfile(variant.id(), variant.presentation())));
        }
        nameVariants.sort(Comparator
            .comparingInt((ListNameVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));
        return new TablistRuntime(doc, List.copyOf(headerVariants), List.copyOf(nameVariants),
            compileSortWeight(doc), sortViewerDependent(doc));
    }

    /** The compiled {@code sort.weight}, or null when the document declares no active sort block. */
    Expr sortWeight() {
        return sortWeight;
    }

    /** Whether the weight reads a {@code viewer.*} variable, which forces a per-viewer pass. */
    boolean sortViewerDependent() {
        return sortViewerDependent;
    }

    private static Expr compileSortWeight(TablistDoc doc) {
        return doc.sort().active() ? ExprParser.parse(doc.sort().weight()) : null;
    }

    private static boolean sortViewerDependent(TablistDoc doc) {
        if (!doc.sort().active()) {
            return false;
        }
        for (String variable : TablistDoc.Sort.references(doc.sort().weight()).variables()) {
            if (variable.startsWith(VIEWER_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    HeaderFooterProfile headerFooter(ExprScope scope, BoundedConditionErrorCallback errors) {
        if (!headerFooterVisible(scope, errors)) {
            return null;
        }
        for (HeaderFooterVariant candidate : headerFooterVariants) {
            if (candidate.condition().matches(scope, errors)) {
                return candidate.profile();
            }
        }
        return baseHeaderFooter;
    }

    ListNameProfile listName(ExprScope scope, BoundedConditionErrorCallback errors) {
        if (!doc.listNames().enabled() || !doc.show().matches(scope, errors)
            || !doc.listNames().show().matches(scope, errors)) {
            return null;
        }
        for (ListNameVariant candidate : listNameVariants) {
            if (candidate.condition().matches(scope, errors)) {
                return candidate.profile();
            }
        }
        return baseListName;
    }

    boolean headerFooterVisible(ExprScope scope, BoundedConditionErrorCallback errors) {
        return doc.headerFooter().enabled() && doc.show().matches(scope, errors)
            && doc.headerFooter().show().matches(scope, errors);
    }

    record HeaderFooterProfile(String id, TablistDoc.HeaderFooterPresentation presentation) {
    }

    /**
     * One instance per list-name profile per document revision, so the tablist tick reads document
     * traits instead of re-parsing the format for every player. {@code usesGroup} is fixed by the
     * document; {@code fastRefresh} also depends on the published conditional-emoji table, which the
     * emoji registry republishes independently of this document, so it is memoised against that
     * generation instead of frozen at compile time.
     */
    static final class ListNameProfile {
        private final String id;
        private final TablistDoc.ListNamePresentation presentation;
        private final boolean usesGroup;
        private volatile CachedFastRefresh fastRefresh;

        private ListNameProfile(String id, TablistDoc.ListNamePresentation presentation) {
            this.id = id;
            this.presentation = presentation;
            this.usesGroup = presentation.format().contains(TablistService.GROUP_TOKEN);
        }

        String id() {
            return id;
        }

        TablistDoc.ListNamePresentation presentation() {
            return presentation;
        }

        boolean usesGroup() {
            return usesGroup;
        }

        boolean fastRefresh() {
            long emojiGeneration = TextPipeline.emojiGeneration();
            CachedFastRefresh current = fastRefresh;
            if (current != null && current.emojiGeneration() == emojiGeneration) {
                return current.value();
            }
            boolean computed = TextPipeline.requiresFastRefresh(presentation.format());
            fastRefresh = new CachedFastRefresh(emojiGeneration, computed);
            return computed;
        }

        private record CachedFastRefresh(long emojiGeneration, boolean value) {
        }
    }

    private record HeaderFooterVariant(TablistDoc.HeaderFooterVariant variant, CompiledCondition condition,
                                       HeaderFooterProfile profile) {
    }

    private record ListNameVariant(TablistDoc.ListNameVariant variant, CompiledCondition condition,
                                   ListNameProfile profile) {
    }
}
