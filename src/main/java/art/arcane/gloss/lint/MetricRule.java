package art.arcane.gloss.lint;

import java.util.regex.Pattern;

/** {@code |metric.<key>|} resolves only while some plugin publishes that key. */
public final class MetricRule extends TextTokenRule {
    public static final String CODE = "metric-unpublished";
    private static final Pattern TOKEN = Pattern.compile("\\|metric\\.([A-Za-z0-9._:-]+)\\|");

    public MetricRule() {
        super(TOKEN);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    boolean resolves(LintContext context, String captured) {
        return context.publishedMetricKeys().contains(captured);
    }

    @Override
    boolean applicable(LintContext context) {
        return !context.publishedMetricKeys().isEmpty();
    }

    @Override
    Severity severity() {
        return Severity.WARNING;
    }

    @Override
    String message(String captured) {
        return "no plugin publishes this metric key: " + captured;
    }
}
