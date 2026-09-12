package art.arcane.gloss.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A file the registries refused for its schemaVersion is invisible until an operator is told. */
public final class SchemaSkippedRule implements LintRule {
    public static final String CODE = "schema-skipped";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, String> skipped : context.skippedSchemaDocuments().entrySet()) {
            int separator = skipped.getKey().indexOf('/');
            String kind = separator < 0 ? skipped.getKey() : skipped.getKey().substring(0, separator);
            String id = separator < 0 ? "" : skipped.getKey().substring(separator + 1);
            diagnostics.add(new Diagnostic(CODE, Severity.WARNING, kind, id, "", skipped.getValue()));
        }
        return List.copyOf(diagnostics);
    }
}
