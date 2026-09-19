package art.arcane.gloss.lint;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-kind id references: an inventory named by an action or a field has to
 * exist. Each reference is checked only while this build actually has that kind, so a workspace on
 * a server without the kind reports nothing instead of reporting everything.
 */
public final class ReferenceRules implements LintRule {
    public static final String CODE = "reference-unknown";
    private static final Map<String, Reference> REFERENCES = Map.of(
            "inventory", new Reference("inventories", "inventory-unknown", "inventory"));

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, Reference> entry : REFERENCES.entrySet()) {
            Reference reference = entry.getValue();
            EditorSyncDocumentKind kind = HistoryKinds.byCollection(reference.collection());
            if (kind == null) {
                continue;
            }
            checkReference(context, entry.getKey(), reference, diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void checkReference(LintContext context, String field, Reference reference,
                                List<Diagnostic> diagnostics) {
        for (LintContext.Document document : context.all()) {
            if (document.kind().equals(reference.collection())) {
                continue;
            }
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject object = element.getAsJsonObject();
                String referenced = JsonWalk.string(object, field);
                if (referenced == null || referenced.isBlank()
                        || !referencesKind(object, field, reference)) {
                    return;
                }
                if (!context.ids(reference.collection()).contains(referenced)) {
                    diagnostics.add(new Diagnostic(reference.code(), Severity.ERROR,
                            document.kind(), document.id(), pointer + "/" + field,
                            reference.noun() + " does not exist: " + referenced));
                }
            });
        }
    }

    private static boolean referencesKind(JsonObject object, String field, Reference reference) {
        String type = JsonWalk.string(object, "type");
        return type == null || type.equals(field) || object.has(field) && !type.equals("text");
    }

    private record Reference(String collection, String code, String noun) {
    }
}
