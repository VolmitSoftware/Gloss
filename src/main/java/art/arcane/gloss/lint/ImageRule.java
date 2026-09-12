package art.arcane.gloss.lint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A text image whose file is missing draws nothing and logs once per viewer. */
public final class ImageRule implements LintRule {
    public static final String CODE = "image-missing";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        Set<String> images = context.imagePaths();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject object = element.getAsJsonObject();
                String type = JsonWalk.string(object, "type");
                if ("textImage".equals(type)) {
                    report(diagnostics, images, document, pointer + "/path",
                            JsonWalk.string(object, "path"));
                    return;
                }
                if (!"animatedTextImage".equals(type)) {
                    return;
                }
                JsonElement source = object.get("source");
                if (source == null) {
                    return;
                }
                if (source.isJsonArray()) {
                    for (int index = 0; index < source.getAsJsonArray().size(); index++) {
                        JsonElement frame = source.getAsJsonArray().get(index);
                        if (frame.isJsonPrimitive()) {
                            report(diagnostics, images, document, pointer + "/source/" + index,
                                    frame.getAsString());
                        }
                    }
                    return;
                }
                if (source.isJsonPrimitive()) {
                    report(diagnostics, images, document, pointer + "/source", source.getAsString());
                }
            });
        }
        return List.copyOf(diagnostics);
    }

    private static void report(List<Diagnostic> diagnostics, Set<String> images,
                               LintContext.Document document, String pointer, String path) {
        if (path == null || path.isBlank() || images.contains(path)) {
            return;
        }
        diagnostics.add(new Diagnostic(CODE, Severity.ERROR, document.kind(), document.id(),
                pointer, "image file is missing: " + path));
    }
}
