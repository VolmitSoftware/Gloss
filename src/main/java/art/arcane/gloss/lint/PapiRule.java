package art.arcane.gloss.lint;

import java.util.Locale;
import java.util.regex.Pattern;

/** A {@code %expansion_key%} placeholder needs its PlaceholderAPI expansion installed. */
public final class PapiRule extends TextTokenRule {
    public static final String CODE = "papi-expansion-missing";
    private static final Pattern TOKEN = Pattern.compile("%([A-Za-z0-9]+)_[A-Za-z0-9_:<>.-]+%");

    public PapiRule() {
        super(TOKEN);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    boolean resolves(LintContext context, String captured) {
        return context.papiExpansions().contains(captured.toLowerCase(Locale.ROOT));
    }

    @Override
    boolean applicable(LintContext context) {
        return !context.papiExpansions().isEmpty();
    }

    @Override
    Severity severity() {
        return Severity.WARNING;
    }

    @Override
    String message(String captured) {
        return "PlaceholderAPI expansion is not installed: " + captured;
    }
}
