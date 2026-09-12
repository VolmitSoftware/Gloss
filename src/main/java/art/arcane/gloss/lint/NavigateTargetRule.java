package art.arcane.gloss.lint;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A navigate action whose target menu does not exist opens nothing. */
public final class NavigateTargetRule implements LintRule {
    public static final String CODE = "dangling-navigate";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        Set<String> menus = context.ids("menus");
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject object = element.getAsJsonObject();
                if (!"navigate".equals(JsonWalk.string(object, "type"))) {
                    return;
                }
                String mode = JsonWalk.string(object, "mode");
                String target = JsonWalk.string(object, "target");
                if (target == null || (mode != null && !mode.equals("push")
                        && !mode.equals("replace"))) {
                    return;
                }
                if (!menus.contains(target)) {
                    diagnostics.add(new Diagnostic(CODE, Severity.ERROR, document.kind(),
                            document.id(), pointer + "/target",
                            "navigates to a menu that does not exist: " + target));
                }
            });
        }
        return List.copyOf(diagnostics);
    }
}
