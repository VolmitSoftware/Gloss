package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.ConditionReferences;
import art.arcane.gloss.condition.ConditionScope;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /gloss expr}: evaluates one expression in the sender's own condition scope and prints the
 * value, its Java type, and for every variable the expression names, which resolver answered it.
 * Namespaces are probed before the scope because that is the order the scopes themselves use.
 */
public final class CommandGlossExpr {
    private static final String UNRESOLVED = "-";

    private final Gloss plugin;

    public CommandGlossExpr(Gloss plugin) {
        this.plugin = plugin;
    }

    /** One variable the expression named, and what answered it. */
    public record Reference(String name, String resolver, String value) {
    }

    /** The whole answer: value and type on success, {@code error} instead when it did not evaluate. */
    public record ExprReport(String value, String type, List<Reference> references, List<String> functions,
                             String error) {
        public ExprReport {
            references = List.copyOf(references);
            functions = List.copyOf(functions);
        }
    }

    public void expr(CommandSender sender, String expression) {
        if (GlossCommandMessages.denied(sender, "gloss.debug.expr")) {
            return;
        }
        ExprReport report = report(expression,
            GlossConditionScope.viewer(plugin, sender instanceof Player player ? player : null));
        if (report.error() != null) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPR_FAILED,
                MessageArgument.untrusted("reason", report.error()));
        } else {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPR_VALUE,
                MessageArgument.untrusted("value", report.value()),
                MessageArgument.untrusted("type", report.type()));
        }
        for (Reference reference : report.references()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPR_REFERENCE,
                MessageArgument.untrusted("name", reference.name()),
                MessageArgument.untrusted("value", reference.value()),
                MessageArgument.untrusted("source", reference.resolver()));
        }
        if (!report.functions().isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPR_FUNCTIONS,
                MessageArgument.untrusted("value", String.join(", ", report.functions())));
        }
    }

    /** Parses, describes every reference, then evaluates; a failure at any step becomes {@code error}. */
    public static ExprReport report(String source, ExprScope scope) {
        Expr parsed;
        try {
            parsed = ExprParser.parse(source);
        } catch (RuntimeException failure) {
            return new ExprReport(null, null, List.of(), List.of(), reason(failure));
        }
        ConditionReferences references = collect(parsed);
        List<Reference> described = new ArrayList<>(references.variables().size());
        for (String name : references.variables()) {
            described.add(describe(name, scope));
        }
        List<String> functions = List.copyOf(references.functions());
        try {
            Object value = ExprEvaluator.eval(parsed, ConditionScope.wrap(scope));
            return new ExprReport(format(value), typeName(value), described, functions, null);
        } catch (RuntimeException failure) {
            return new ExprReport(null, null, described, functions, reason(failure));
        }
    }

    private static Reference describe(String name, ExprScope scope) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            String prefix = name.substring(0, dot);
            if (ExprVariableNamespaces.global().isRegistered(prefix)) {
                Object value = ExprVariableNamespaces.global().resolve(name, scope.variableContext());
                if (value != null) {
                    return new Reference(name, "namespace " + prefix, format(value));
                }
            }
        }
        Object value = scope.variable(name);
        return value == null
            ? new Reference(name, "unresolved", UNRESOLVED)
            : new Reference(name, "scope", format(value));
    }

    private static ConditionReferences collect(Expr expression) {
        Set<String> variables = new TreeSet<>();
        Set<String> functions = new TreeSet<>();
        walk(expression, variables, functions);
        return new ConditionReferences(variables, functions, new LinkedHashSet<>());
    }

    private static void walk(Expr expression, Set<String> variables, Set<String> functions) {
        switch (expression) {
            case Expr.Var variable -> variables.add(variable.name());
            case Expr.Unary unary -> walk(unary.operand(), variables, functions);
            case Expr.Binary binary -> {
                walk(binary.left(), variables, functions);
                walk(binary.right(), variables, functions);
            }
            case Expr.Ternary ternary -> {
                walk(ternary.condition(), variables, functions);
                walk(ternary.ifTrue(), variables, functions);
                walk(ternary.ifFalse(), variables, functions);
            }
            case Expr.Call call -> {
                functions.add(call.name());
                for (Expr argument : call.args()) {
                    walk(argument, variables, functions);
                }
            }
            case Expr.ListLiteral list -> {
                for (Expr item : list.items()) {
                    walk(item, variables, functions);
                }
            }
            default -> {
            }
        }
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof List<?> ? "List" : value.getClass().getSimpleName();
    }

    private static String format(Object value) {
        if (value == null) {
            return UNRESOLVED;
        }
        if (value instanceof Double number) {
            double raw = number;
            return raw == Math.rint(raw) && !Double.isInfinite(raw)
                ? String.valueOf((long) raw) : String.valueOf(raw);
        }
        return String.valueOf(value);
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
