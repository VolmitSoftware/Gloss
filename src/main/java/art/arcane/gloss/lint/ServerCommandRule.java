package art.arcane.gloss.lint;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A pack-owned document that runs commands as the server is worth an operator's attention. */
public final class ServerCommandRule implements LintRule {
    public static final String CODE = "server-command-in-pack";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        if (context.packOwnedPaths().isEmpty()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            if (!context.packOwnedPaths().contains(relativePath(document))) {
                continue;
            }
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject object = element.getAsJsonObject();
                if (!"command".equals(JsonWalk.string(object, "type"))
                        || !"server".equals(JsonWalk.string(object, "source"))) {
                    return;
                }
                diagnostics.add(new Diagnostic(CODE, Severity.WARNING, document.kind(),
                        document.id(), pointer,
                        "an installed pack runs this command as the server"));
            });
        }
        return List.copyOf(diagnostics);
    }

    private static String relativePath(LintContext.Document document) {
        EditorSyncDocumentKind kind = HistoryKinds.byCollection(document.kind());
        if (kind == null) {
            return document.kind() + "/" + document.id() + ".json";
        }
        return kind.layout() == EditorSyncDocumentKind.Layout.SINGLE
                ? kind.storageName()
                : kind.storageName() + "/" + document.id() + ".json";
    }
}
