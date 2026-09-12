package art.arcane.gloss.lint;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A permission node no plugin declares is never granted by a permissions manager's wildcards. */
public final class PermissionRule implements LintRule {
    public static final String CODE = "permission-unknown";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        if (context.declaredPermissions().isEmpty()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject object = element.getAsJsonObject();
                String node = JsonWalk.string(object, "permission");
                if (node == null || node.isBlank()
                        || context.declaredPermissions().contains(node)) {
                    return;
                }
                diagnostics.add(new Diagnostic(CODE, Severity.WARNING, document.kind(),
                        document.id(), pointer + "/permission",
                        "no installed plugin declares this permission: " + node));
            });
        }
        return List.copyOf(diagnostics);
    }
}
