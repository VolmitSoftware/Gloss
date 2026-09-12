package art.arcane.gloss.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The server list ping has no viewer, so a viewer token there renders as nothing. */
public final class ViewerTokenRule implements LintRule {
    public static final String CODE = "viewer-token-on-viewerless-surface";
    private static final Set<String> VIEWERLESS_KINDS = Set.of("motd");
    private static final Pattern VIEWER_TOKEN = Pattern.compile(
            "(%player_[A-Za-z0-9_]+%|\\bplayer\\.[A-Za-z]+|\\bviewer\\.[A-Za-z]+)");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Diagnostic> check(LintContext context) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (LintContext.Document document : context.all()) {
            if (!VIEWERLESS_KINDS.contains(document.kind())) {
                continue;
            }
            JsonWalk.visit(document.json(), (pointer, element) -> {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    return;
                }
                Matcher matcher = VIEWER_TOKEN.matcher(element.getAsString());
                while (matcher.find()) {
                    diagnostics.add(new Diagnostic(CODE, Severity.WARNING, document.kind(),
                            document.id(), pointer,
                            "the server list has no viewer, so " + matcher.group(1)
                                    + " renders empty"));
                }
            });
        }
        return List.copyOf(diagnostics);
    }
}
