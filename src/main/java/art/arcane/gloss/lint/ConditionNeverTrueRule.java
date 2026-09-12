package art.arcane.gloss.lint;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A condition written as a literal false hides the thing it guards forever. */
public final class ConditionNeverTrueRule implements LintRule {
    public static final String CODE = "condition-never-true";
    private static final Set<String> CONDITION_FIELDS = Set.of("show", "when", "visible");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            JsonWalk.visit(document.json(), (pointer, element) -> {
                int separator = pointer.lastIndexOf('/');
                if (separator < 0) {
                    return;
                }
                String field = pointer.substring(separator + 1);
                if (!CONDITION_FIELDS.contains(field) || !literalFalse(element)) {
                    return;
                }
                diagnostics.add(new Diagnostic(CODE, Severity.WARNING, document.kind(),
                        document.id(), pointer, field + " is always false, so this never shows"));
            });
        }
        return List.copyOf(diagnostics);
    }

    private static boolean literalFalse(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return false;
        }
        if (element.getAsJsonPrimitive().isBoolean()) {
            return !element.getAsBoolean();
        }
        if (!element.getAsJsonPrimitive().isString()) {
            return false;
        }
        String value = element.getAsString().strip();
        return value.equals("false") || value.equals("{{ false }}") || value.equals("{{false}}");
    }
}
