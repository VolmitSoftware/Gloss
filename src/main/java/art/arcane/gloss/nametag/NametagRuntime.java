package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.text.TextPipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

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
    private final Set<String> subjectProperties;

    private NametagRuntime(String id, NametagDoc doc, CompiledCondition selectWhen,
                           List<CompiledVariant> variants, boolean viewerDependent) {
        this.id = id;
        this.doc = doc;
        this.selectWhen = selectWhen;
        this.variants = variants;
        this.viewerDependent = viewerDependent;
        Set<String> properties = new HashSet<>(Set.of("subject.name", "subject.uuid", "world.uuid"));
        collectProperties(ExprParser.parse(doc.show().expression()), properties);
        collectProperties(ExprParser.parse(selectWhen.source().expression()), properties);
        collectTextProperties(doc.presentation().prefix(), properties);
        collectTextProperties(doc.presentation().suffix(), properties);
        for (CompiledVariant variant : variants) {
            collectProperties(ExprParser.parse(variant.condition().source().expression()), properties);
            collectTextProperties(variant.variant().presentation().prefix(), properties);
            collectTextProperties(variant.variant().presentation().suffix(), properties);
        }
        this.subjectProperties = Set.copyOf(properties);
    }

    public static NametagRuntime compile(String id, NametagDoc doc) {
        CompiledCondition selectWhen = ConditionCompiler.compile(
            ConditionSource.subjectPermission("nametags." + id + ".select.when", doc.select().when(), doc.select().permission()));
        List<CompiledVariant> compiled = new ArrayList<>(doc.variants().size());
        for (NametagDoc.Variant variant : doc.variants()) {
            compiled.add(new CompiledVariant(variant, ConditionCompiler.compile(ConditionSource.subjectPermission(
                "nametags." + id + ".variants." + variant.id() + ".when", variant.when(), variant.permission()))));
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
        if (viewerDependent || conditionalEmoji(doc.presentation())) {
            return true;
        }
        for (CompiledVariant variant : variants) {
            if (conditionalEmoji(variant.variant().presentation())) {
                return true;
            }
        }
        return false;
    }

    public Set<String> subjectProperties() {
        return subjectProperties;
    }

    private static boolean conditionalEmoji(NametagDoc.Presentation presentation) {
        return ((TextPipeline.classify(presentation.prefix()) | TextPipeline.classify(presentation.suffix()))
            & TextPipeline.HAS_PLACEHOLDER) != 0;
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
        if (readsViewerCondition(doc.show().expression())
            || uncertain(ExprParser.parse(doc.show().expression())) || readsViewer(selectWhen)) {
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
            if (variable.startsWith(VIEWER_PREFIX) || variable.startsWith("player.")) {
                return true;
            }
        }
        return readsViewerCondition(condition.source().expression())
            || uncertain(ExprParser.parse(condition.source().expression()));
    }

    private static boolean readsViewer(NametagDoc.Presentation presentation) {
        return readsViewer(presentation.prefix()) || readsViewer(presentation.suffix());
    }

    private static boolean readsViewer(String raw) {
        if (raw == null) {
            return false;
        }
        if (readsViewerCondition(raw) || raw.contains("papi(") || raw.indexOf('%') >= 0) {
            return true;
        }
        int expressionStart = raw.indexOf("{{");
        while (expressionStart >= 0) {
            int end = raw.indexOf("}}", expressionStart + 2);
            if (end < 0) {
                return true;
            }
            try {
                if (uncertain(ExprParser.parse(raw.substring(expressionStart + 2, end)))) {
                    return true;
                }
            } catch (RuntimeException failure) {
                return true;
            }
            expressionStart = raw.indexOf("{{", end + 2);
        }
        int open = raw.indexOf('|');
        while (open >= 0) {
            int close = raw.indexOf('|', open + 1);
            if (close < 0) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static boolean readsViewerCondition(String raw) {
        return raw != null && (raw.contains(VIEWER_PREFIX) || raw.contains("player.")
            || raw.contains("'viewer'") || raw.contains("\"viewer\""));
    }

    private static boolean uncertain(Expr expression) {
        return switch (expression) {
            case Expr.Var variable -> !variable.name().startsWith("subject.")
                && !variable.name().startsWith("world.") && !variable.name().startsWith("time.")
                && !variable.name().startsWith("server.") && !variable.name().startsWith("metric.");
            case Expr.Call call -> {
                boolean roleFunction = Set.of("hasPermission", "inGroup", "inRegion", "isBedrock", "papi", "papiNumber")
                    .contains(call.name());
                boolean subjectCall = roleFunction && !call.args().isEmpty()
                    && call.args().getFirst() instanceof Expr.Str role && role.value().equals("subject");
                yield (!ExprFunctions.isBuiltIn(call.name()) && !subjectCall && !call.name().equals("metric"))
                    || call.args().stream().anyMatch(NametagRuntime::uncertain);
            }
            case Expr.Unary unary -> uncertain(unary.operand());
            case Expr.Binary binary -> uncertain(binary.left()) || uncertain(binary.right());
            case Expr.Ternary ternary -> uncertain(ternary.condition()) || uncertain(ternary.ifTrue()) || uncertain(ternary.ifFalse());
            case Expr.ListLiteral list -> list.items().stream().anyMatch(NametagRuntime::uncertain);
            default -> false;
        };
    }

    private static void collectTextProperties(String raw, Set<String> properties) {
        int start = raw.indexOf("{{");
        while (start >= 0) {
            int end = raw.indexOf("}}", start + 2);
            if (end < 0) {
                return;
            }
            try {
                collectProperties(ExprParser.parse(raw.substring(start + 2, end)), properties);
            } catch (RuntimeException ignored) {
                return;
            }
            start = raw.indexOf("{{", end + 2);
        }
    }

    private static void collectProperties(Expr expression, Set<String> properties) {
        switch (expression) {
            case Expr.Var variable -> {
                if (variable.name().startsWith("subject.") || variable.name().startsWith("world.")) {
                    String name = variable.name();
                    properties.add(name.equals("subject.username") || name.equals("subject.displayName") ? "subject.name" : name);
                }
            }
            case Expr.Call call -> call.args().forEach(argument -> collectProperties(argument, properties));
            case Expr.Unary unary -> collectProperties(unary.operand(), properties);
            case Expr.Binary binary -> {
                collectProperties(binary.left(), properties);
                collectProperties(binary.right(), properties);
            }
            case Expr.Ternary ternary -> {
                collectProperties(ternary.condition(), properties);
                collectProperties(ternary.ifTrue(), properties);
                collectProperties(ternary.ifFalse(), properties);
            }
            case Expr.ListLiteral list -> list.items().forEach(item -> collectProperties(item, properties));
            default -> { }
        }
    }

    public record Profile(String id, NametagDoc.Presentation presentation) {
    }

    private record CompiledVariant(NametagDoc.Variant variant, CompiledCondition condition) {
    }
}
