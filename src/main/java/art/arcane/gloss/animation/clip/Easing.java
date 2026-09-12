package art.arcane.gloss.animation.clip;

import java.util.Locale;

public enum Easing {
    LINEAR,
    HOLD,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    BACK_OUT,
    BEZIER;

    private static final String BEZIER_PREFIX = "bezier(";
    private static final int NEWTON_ITERATIONS = 8;
    private static final int BISECTION_ITERATIONS = 24;
    private static final double SOLVE_EPSILON = 1.0E-7D;

    public static Curve parse(String source) {
        String normalized = source == null ? "" : source.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("easing must not be blank");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith(BEZIER_PREFIX)) {
            return parseBezier(normalized, lower);
        }
        try {
            Easing named = valueOf(normalized.toUpperCase(Locale.ROOT));
            if (named == BEZIER) {
                throw new IllegalArgumentException("bezier easing needs four control values: bezier(x1,y1,x2,y2)");
            }
            return Curve.of(named);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown easing '" + normalized + "'", failure);
        }
    }

    private static Curve parseBezier(String source, String lower) {
        if (!lower.endsWith(")")) {
            throw new IllegalArgumentException("bezier easing must end with ')': " + source);
        }
        String[] parts = lower.substring(BEZIER_PREFIX.length(), lower.length() - 1).split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bezier easing needs four control values: " + source);
        }
        float[] values = new float[4];
        for (int index = 0; index < 4; index++) {
            try {
                values[index] = Float.parseFloat(parts[index].trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("bezier control value is not a number: " + source, failure);
            }
        }
        if (values[0] < 0.0F || values[0] > 1.0F || values[2] < 0.0F || values[2] > 1.0F) {
            throw new IllegalArgumentException("bezier x control values must be within 0..1: " + source);
        }
        return new Curve(BEZIER, values[0], values[1], values[2], values[3]);
    }

    public record Curve(Easing easing, float x1, float y1, float x2, float y2) {
        private static final Curve LINEAR_CURVE = new Curve(LINEAR, 0.0F, 0.0F, 0.0F, 0.0F);
        private static final Curve HOLD_CURVE = new Curve(HOLD, 0.0F, 0.0F, 0.0F, 0.0F);
        private static final Curve EASE_IN_CURVE = new Curve(EASE_IN, 0.0F, 0.0F, 0.0F, 0.0F);
        private static final Curve EASE_OUT_CURVE = new Curve(EASE_OUT, 0.0F, 0.0F, 0.0F, 0.0F);
        private static final Curve EASE_IN_OUT_CURVE = new Curve(EASE_IN_OUT, 0.0F, 0.0F, 0.0F, 0.0F);
        private static final Curve BACK_OUT_CURVE = new Curve(BACK_OUT, 0.0F, 0.0F, 0.0F, 0.0F);

        public Curve {
            if (easing == null) {
                throw new IllegalArgumentException("easing curve needs an easing");
            }
        }

        public static Curve of(Easing easing) {
            return switch (easing) {
                case LINEAR -> LINEAR_CURVE;
                case HOLD -> HOLD_CURVE;
                case EASE_IN -> EASE_IN_CURVE;
                case EASE_OUT -> EASE_OUT_CURVE;
                case EASE_IN_OUT -> EASE_IN_OUT_CURVE;
                case BACK_OUT -> BACK_OUT_CURVE;
                case BEZIER -> throw new IllegalArgumentException("bezier curves need control values");
            };
        }

        public double apply(double progress) {
            double selected = Math.max(0.0D, Math.min(1.0D, progress));
            return switch (easing) {
                case HOLD -> 0.0D;
                case LINEAR -> selected;
                case EASE_IN -> selected * selected * selected;
                case EASE_OUT -> 1.0D - Math.pow(1.0D - selected, 3.0D);
                case EASE_IN_OUT -> selected < 0.5D
                    ? 4.0D * selected * selected * selected
                    : 1.0D - Math.pow(-2.0D * selected + 2.0D, 3.0D) / 2.0D;
                case BACK_OUT -> {
                    double shifted = selected - 1.0D;
                    yield 1.0D + 2.70158D * shifted * shifted * shifted + 1.70158D * shifted * shifted;
                }
                case BEZIER -> bezier(selected);
            };
        }

        private double bezier(double x) {
            if (x <= 0.0D) {
                return 0.0D;
            }
            if (x >= 1.0D) {
                return 1.0D;
            }
            return sampleAxis(solveParameter(x), y1, y2);
        }

        private double solveParameter(double x) {
            double parameter = x;
            for (int iteration = 0; iteration < NEWTON_ITERATIONS; iteration++) {
                double error = sampleAxis(parameter, x1, x2) - x;
                if (Math.abs(error) < SOLVE_EPSILON) {
                    return parameter;
                }
                double slope = sampleSlope(parameter, x1, x2);
                if (Math.abs(slope) < SOLVE_EPSILON) {
                    break;
                }
                parameter -= error / slope;
            }
            double low = 0.0D;
            double high = 1.0D;
            parameter = x;
            for (int iteration = 0; iteration < BISECTION_ITERATIONS && high - low > SOLVE_EPSILON; iteration++) {
                double error = sampleAxis(parameter, x1, x2) - x;
                if (error > 0.0D) {
                    high = parameter;
                } else {
                    low = parameter;
                }
                parameter = (low + high) * 0.5D;
            }
            return parameter;
        }

        private static double sampleAxis(double t, float control1, float control2) {
            double inverse = 1.0D - t;
            return 3.0D * inverse * inverse * t * control1 + 3.0D * inverse * t * t * control2 + t * t * t;
        }

        private static double sampleSlope(double t, float control1, float control2) {
            double inverse = 1.0D - t;
            return 3.0D * inverse * inverse * control1
                + 6.0D * inverse * t * (control2 - control1)
                + 3.0D * t * t * (1.0D - control2);
        }
    }
}
