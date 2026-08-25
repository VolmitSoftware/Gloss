package art.arcane.gloss.tab;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TablistRuntime {
    private final TablistDoc doc;
    private final List<HeaderFooterVariant> headerFooterVariants;
    private final List<ListNameVariant> listNameVariants;

    private TablistRuntime(TablistDoc doc, List<HeaderFooterVariant> headerFooterVariants,
                           List<ListNameVariant> listNameVariants) {
        this.doc = doc;
        this.headerFooterVariants = headerFooterVariants;
        this.listNameVariants = listNameVariants;
    }

    static TablistRuntime compile(TablistDoc doc) {
        List<HeaderFooterVariant> headerVariants = new ArrayList<>(doc.headerFooter().variants().size());
        for (TablistDoc.HeaderFooterVariant variant : doc.headerFooter().variants()) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "tablist.headerFooter.variants." + variant.id() + ".when", variant.when()));
            headerVariants.add(new HeaderFooterVariant(variant, condition));
        }
        headerVariants.sort(Comparator
            .comparingInt((HeaderFooterVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));

        List<ListNameVariant> nameVariants = new ArrayList<>(doc.listNames().variants().size());
        for (TablistDoc.ListNameVariant variant : doc.listNames().variants()) {
            CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
                "tablist.listNames.variants." + variant.id() + ".when", variant.when()));
            nameVariants.add(new ListNameVariant(variant, condition));
        }
        nameVariants.sort(Comparator
            .comparingInt((ListNameVariant value) -> value.variant().priority()).reversed()
            .thenComparing(value -> value.variant().id()));
        return new TablistRuntime(doc, List.copyOf(headerVariants), List.copyOf(nameVariants));
    }

    HeaderFooterProfile headerFooter(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (HeaderFooterVariant candidate : headerFooterVariants) {
            if (candidate.condition().matches(scope, errors)) {
                return new HeaderFooterProfile(candidate.variant().id(), candidate.variant().presentation());
            }
        }
        return new HeaderFooterProfile("base", doc.headerFooter().presentation());
    }

    ListNameProfile listName(ExprScope scope, BoundedConditionErrorCallback errors) {
        for (ListNameVariant candidate : listNameVariants) {
            if (candidate.condition().matches(scope, errors)) {
                return new ListNameProfile(candidate.variant().id(), candidate.variant().presentation());
            }
        }
        return new ListNameProfile("base", doc.listNames().presentation());
    }

    record HeaderFooterProfile(String id, TablistDoc.HeaderFooterPresentation presentation) {
    }

    record ListNameProfile(String id, TablistDoc.ListNamePresentation presentation) {
    }

    private record HeaderFooterVariant(TablistDoc.HeaderFooterVariant variant, CompiledCondition condition) {
    }

    private record ListNameVariant(TablistDoc.ListNameVariant variant, CompiledCondition condition) {
    }
}
