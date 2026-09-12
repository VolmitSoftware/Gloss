package art.arcane.gloss.marker;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;

import java.util.Objects;

/**
 * A marker with its conditions compiled once. The document's {@code show} and the marker's own
 * {@code audience} both have to pass; {@code distanceScale} is evaluated per render and falls back
 * to 1 when the expression cannot produce a finite number.
 */
public final class MarkerRuntime {
    private static final double DEFAULT_SCALE = 1.0D;

    private final MarkerSpec spec;
    private final ShowCondition show;
    private final Expr distanceScale;

    private MarkerRuntime(MarkerSpec spec, ShowCondition show) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.show = show == null ? ShowCondition.ALWAYS : show;
        this.distanceScale = spec.distanceScale() == null ? null : ExprParser.parse(spec.distanceScale());
    }

    public static MarkerRuntime of(MarkerSpec spec) {
        return new MarkerRuntime(spec, ShowCondition.ALWAYS);
    }

    public static MarkerRuntime of(MarkerSpec spec, ShowCondition show) {
        return new MarkerRuntime(spec, show);
    }

    public MarkerSpec spec() {
        return spec;
    }

    public String id() {
        return spec.id();
    }

    public boolean visible(ExprScope scope) {
        return show.matches(scope) && spec.audience().matches(scope);
    }

    public double scale(ExprScope scope) {
        if (distanceScale == null) {
            return DEFAULT_SCALE;
        }
        try {
            double value = ExprEvaluator.number(distanceScale, scope);
            return Double.isFinite(value) ? Math.clamp(value, 0.01D, 16.0D) : DEFAULT_SCALE;
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "marker-scale:" + spec.id(), failure,
                "Marker %s distanceScale failed and was treated as 1.", spec.id());
            return DEFAULT_SCALE;
        }
    }
}
