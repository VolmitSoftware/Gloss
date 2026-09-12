package art.arcane.gloss.lint;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A world panel whose root menu is missing renders nothing at all. */
public final class PanelRootRule implements LintRule {
    public static final String CODE = "panel-root-missing";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        Set<String> menus = context.ids("menus");
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, JsonElement> panel : context.documents("panels").entrySet()) {
            if (!panel.getValue().isJsonObject()) {
                continue;
            }
            String root = JsonWalk.string(panel.getValue().getAsJsonObject(), "rootMenuId");
            if (root != null && !menus.contains(root)) {
                diagnostics.add(new Diagnostic(CODE, Severity.ERROR, "panels", panel.getKey(),
                        "/rootMenuId", "root menu does not exist: " + root));
            }
        }
        return List.copyOf(diagnostics);
    }
}
