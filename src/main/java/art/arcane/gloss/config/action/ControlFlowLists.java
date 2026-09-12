package art.arcane.gloss.config.action;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionValidationException;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared validation for the control-flow records: nested lists, role names and expression fields. */
final class ControlFlowLists {
    static final Set<String> ROLES = Set.of("viewer", "subject", "source");

    private ControlFlowLists() {
    }

    static List<MenuActionData> copy(List<MenuActionData> list) {
        if (list == null) {
            return List.of();
        }
        List<MenuActionData> copy = new ArrayList<>(list.size());
        for (MenuActionData action : list) {
            if (action != null) {
                copy.add(action);
            }
        }
        return List.copyOf(copy);
    }

    @SafeVarargs
    static List<MenuActionData> concat(List<MenuActionData>... lists) {
        List<MenuActionData> all = new ArrayList<>();
        for (List<MenuActionData> list : lists) {
            if (list != null) {
                all.addAll(list);
            }
        }
        return List.copyOf(all);
    }

    /** @return why {@code expression} does not parse, or null */
    static String expressionProblem(String expression, String field) {
        if (expression == null || expression.isBlank()) {
            return "declares no " + field + " expression";
        }
        try {
            ExprParser.parse(expression);
            return null;
        } catch (ExprException invalid) {
            return "declares an invalid " + field + " expression: " + invalid.getMessage();
        }
    }

    /** @return why {@code condition} does not compile, or null */
    static String conditionProblem(String condition, String field) {
        if (condition == null || condition.isBlank()) {
            return "declares no " + field + " condition";
        }
        try {
            ConditionCompiler.compile(condition);
            return null;
        } catch (ConditionValidationException invalid) {
            return "declares an invalid " + field + " condition: " + invalid.getMessage();
        }
    }

    static String roleProblem(String role, String field) {
        return role == null || ROLES.contains(role) ? null
            : "declares " + field + " \"" + role + "\"; expected viewer, subject, or source";
    }
}
