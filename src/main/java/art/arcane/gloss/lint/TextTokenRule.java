package art.arcane.gloss.lint;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shape every token rule shares: walk every string in every document, pull one token pattern
 * out of it, and report the ones the workspace cannot resolve.
 */
abstract class TextTokenRule implements LintRule {
    private final Pattern token;

    TextTokenRule(Pattern token) {
        this.token = token;
    }

    abstract boolean resolves(LintContext context, String captured);

    abstract Severity severity();

    abstract String message(String captured);

    /** False when the workspace cannot answer the question, so the rule stays quiet. */
    boolean applicable(LintContext context) {
        return true;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        if (!applicable(context)) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    return;
                }
                collect(context, document, pointer, element.getAsString(), diagnostics);
            });
        }
        return List.copyOf(diagnostics);
    }

    private void collect(LintContext context, LintContext.Document document, String pointer,
                         String text, List<Diagnostic> diagnostics) {
        Matcher matcher = token.matcher(text);
        while (matcher.find()) {
            String captured = matcher.group(1);
            if (resolves(context, captured)) {
                continue;
            }
            diagnostics.add(new Diagnostic(code(), severity(), document.kind(), document.id(),
                    pointer, message(captured)));
        }
    }

    static JsonElement noop() {
        return null;
    }
}
