package art.arcane.gloss.lint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Two variants at the same priority resolve by id, which is rarely what an author meant. */
public final class VariantPriorityRule implements LintRule {
    public static final String CODE = "variant-priority-duplicate";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonArray() || !pointer.endsWith("/variants")) {
                    return;
                }
                Map<Integer, String> byPriority = new HashMap<>();
                for (JsonElement value : element.getAsJsonArray()) {
                    if (!value.isJsonObject()) {
                        continue;
                    }
                    JsonObject variant = value.getAsJsonObject();
                    JsonElement priority = variant.get("priority");
                    if (priority == null || !priority.isJsonPrimitive()
                            || !priority.getAsJsonPrimitive().isNumber()) {
                        continue;
                    }
                    String id = JsonWalk.string(variant, "id");
                    String previous = byPriority.putIfAbsent(priority.getAsInt(),
                            id == null ? "" : id);
                    if (previous != null) {
                        diagnostics.add(new Diagnostic(CODE, Severity.WARNING, document.kind(),
                                document.id(), pointer,
                                "two variants share priority " + priority.getAsInt() + ": "
                                        + previous + " and " + (id == null ? "" : id)));
                    }
                }
            });
        }
        return List.copyOf(diagnostics);
    }
}
