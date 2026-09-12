package art.arcane.gloss.lint;

import java.util.regex.Pattern;

/** {@code |animation.<id>|} renders as literal text when the animation is not loaded. */
public final class AnimationRule extends TextTokenRule {
    public static final String CODE = "animation-unknown";
    private static final Pattern TOKEN = Pattern.compile("\\|animation\\.([A-Za-z0-9._-]+)\\|");

    public AnimationRule() {
        super(TOKEN);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    boolean resolves(LintContext context, String captured) {
        return context.ids("animations").contains(captured);
    }

    @Override
    Severity severity() {
        return Severity.ERROR;
    }

    @Override
    String message(String captured) {
        return "animation is not loaded: " + captured;
    }
}
