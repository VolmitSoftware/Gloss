package art.arcane.gloss.bubble;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class BubbleMotionPlan {
    private static final int MAX_SOURCE_LENGTH = 512;
    private static final Set<String> VARIABLES = Set.of(
        "t", "remaining", "ageMs", "lifetimeMs", "stackIndex", "stackCount", "lineCount", "stackY",
        "seed", "pi");

    private final Expr translationX;
    private final Expr translationY;
    private final Expr translationZ;
    private final Expr scaleX;
    private final Expr scaleY;
    private final Expr scaleZ;
    private final Expr rotationX;
    private final Expr rotationY;
    private final Expr rotationZ;
    private final Expr opacity;
    private final String diagnosticLabel;
    private final AtomicBoolean runtimeFailureWarned;

    private BubbleMotionPlan(BubbleStyleDoc.Motion motion) {
        this(motion.translation(), motion.scale(), motion.rotation(), motion.opacity(), "bubble style");
    }

    private BubbleMotionPlan(BubbleStyleDoc.Axis translation, BubbleStyleDoc.Axis scale,
                             BubbleStyleDoc.Axis rotation, String opacitySource, String diagnosticLabel) {
        translationX = compile("motion.translation.x", translation.x());
        translationY = compile("motion.translation.y", translation.y());
        translationZ = compile("motion.translation.z", translation.z());
        scaleX = compile("motion.scale.x", scale.x());
        scaleY = compile("motion.scale.y", scale.y());
        scaleZ = compile("motion.scale.z", scale.z());
        rotationX = compile("motion.rotation.x", rotation.x());
        rotationY = compile("motion.rotation.y", rotation.y());
        rotationZ = compile("motion.rotation.z", rotation.z());
        opacity = compile("motion.opacity", opacitySource);
        this.diagnosticLabel = diagnosticLabel;
        this.runtimeFailureWarned = new AtomicBoolean();
        validateSamples();
    }

    static BubbleMotionPlan compile(BubbleStyleDoc.Motion motion) {
        return new BubbleMotionPlan(motion);
    }

    static BubbleMotionPlan compile(BubbleStyleDoc.Motion motion, String diagnosticLabel) {
        return new BubbleMotionPlan(motion.translation(), motion.scale(), motion.rotation(), motion.opacity(),
            diagnosticLabel);
    }

    static void validate(BubbleStyleDoc.Motion motion) {
        new BubbleMotionPlan(motion);
    }

    static void validate(BubbleStyleDoc.Axis translation, BubbleStyleDoc.Axis scale,
                         BubbleStyleDoc.Axis rotation, String opacity) {
        new BubbleMotionPlan(translation, scale, rotation, opacity, "bubble style validation");
    }

    BubbleMotionSample sample(BubbleMotionContext context) {
        MotionScope scope = new MotionScope(context);
        double x = evaluate("motion.translation.x", translationX, scope, 0.0D, -64.0D, 64.0D);
        double y = evaluate("motion.translation.y", translationY, scope, 0.0D, -64.0D, 64.0D);
        double z = evaluate("motion.translation.z", translationZ, scope, 0.0D, -64.0D, 64.0D);
        HologramPresentation presentation = new HologramPresentation(
            evaluate("motion.scale.x", scaleX, scope, 1.0D, 0.0D, 16.0D),
            evaluate("motion.scale.y", scaleY, scope, 1.0D, 0.0D, 16.0D),
            evaluate("motion.scale.z", scaleZ, scope, 1.0D, 0.0D, 16.0D),
            evaluate("motion.rotation.x", rotationX, scope, 0.0D, -Double.MAX_VALUE, Double.MAX_VALUE),
            evaluate("motion.rotation.y", rotationY, scope, 0.0D, -Double.MAX_VALUE, Double.MAX_VALUE),
            evaluate("motion.rotation.z", rotationZ, scope, 0.0D, -Double.MAX_VALUE, Double.MAX_VALUE),
            evaluate("motion.opacity", opacity, scope, 1.0D, 0.0D, 1.0D));
        return new BubbleMotionSample(x, y, z, presentation);
    }

    private static Expr compile(String path, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-blank expression");
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(path + " exceeds " + MAX_SOURCE_LENGTH + " characters");
        }
        try {
            Expr expression = ExprParser.parse(source);
            validateTree(expression);
            return expression;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(path + ": " + failure.getMessage(), failure);
        }
    }

    private static void validateTree(Expr expression) {
        switch (expression) {
            case Expr.Num ignored -> {
            }
            case Expr.Str ignored -> {
            }
            case Expr.Bool ignored -> {
            }
            case Expr.Var variable -> {
                if (!VARIABLES.contains(variable.name())) {
                    throw new IllegalArgumentException("unknown motion variable: " + variable.name());
                }
            }
            case Expr.ListLiteral list -> list.items().forEach(BubbleMotionPlan::validateTree);
            case Expr.Unary unary -> validateTree(unary.operand());
            case Expr.Binary binary -> {
                validateTree(binary.left());
                validateTree(binary.right());
            }
            case Expr.Ternary ternary -> {
                validateTree(ternary.condition());
                validateTree(ternary.ifTrue());
                validateTree(ternary.ifFalse());
            }
            case Expr.Call call -> {
                if (!ExprFunctions.isSupported(call.name())) {
                    throw new IllegalArgumentException("unknown motion function: " + call.name());
                }
                call.args().forEach(BubbleMotionPlan::validateTree);
            }
        }
    }

    private void validateSamples() {
        validateSample(new BubbleMotionContext(0.0D, 0.0D, 5000.0D, 0, 1, 1, 1.12D, 0.5D));
        validateSample(new BubbleMotionContext(0.25D, 1250.0D, 5000.0D, 0, 1, 1, 1.12D, 0.5D));
        validateSample(new BubbleMotionContext(0.5D, 2500.0D, 5000.0D, 0, 1, 1, 1.12D, 0.5D));
        validateSample(new BubbleMotionContext(0.75D, 3750.0D, 5000.0D, 0, 1, 1, 1.12D, 0.5D));
        validateSample(new BubbleMotionContext(1.0D, 5000.0D, 5000.0D, 0, 1, 1, 1.12D, 0.5D));
    }

    private void validateSample(BubbleMotionContext context) {
        MotionScope scope = new MotionScope(context);
        validateFinite("motion.translation.x", translationX, scope);
        validateFinite("motion.translation.y", translationY, scope);
        validateFinite("motion.translation.z", translationZ, scope);
        validateFinite("motion.scale.x", scaleX, scope);
        validateFinite("motion.scale.y", scaleY, scope);
        validateFinite("motion.scale.z", scaleZ, scope);
        validateFinite("motion.rotation.x", rotationX, scope);
        validateFinite("motion.rotation.y", rotationY, scope);
        validateFinite("motion.rotation.z", rotationZ, scope);
        validateFinite("motion.opacity", opacity, scope);
    }

    private static void validateFinite(String path, Expr expression, MotionScope scope) {
        try {
            double value = ExprEvaluator.number(expression, scope);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(path + " result must be finite");
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(path + ": " + failure.getMessage(), failure);
        }
    }

    private double evaluate(String path, Expr expression, MotionScope scope, double fallback, double minimum,
                            double maximum) {
        try {
            double value = ExprEvaluator.number(expression, scope);
            if (!Double.isFinite(value)) {
                warnRuntime(path, "result was not finite");
                return fallback;
            }
            return Math.max(minimum, Math.min(maximum, value));
        } catch (RuntimeException failure) {
            warnRuntime(path, failure.getMessage());
            return fallback;
        }
    }

    private void warnRuntime(String path, String detail) {
        if (runtimeFailureWarned.compareAndSet(false, true)) {
            Gloss.warn(diagnosticLabel + " " + path + " failed at runtime; neutral fallback applied"
                + (detail == null || detail.isBlank() ? "." : ": " + detail));
        }
    }

    record BubbleMotionContext(double t, double ageMs, double lifetimeMs, int stackIndex, int stackCount,
                               int lineCount, double stackY, double seed) {
        BubbleMotionContext {
            t = Math.max(0.0D, Math.min(1.0D, t));
            ageMs = Math.max(0.0D, ageMs);
            lifetimeMs = Math.max(1.0D, lifetimeMs);
        }
    }

    record BubbleMotionSample(double translationX, double translationY, double translationZ,
                              HologramPresentation presentation) {
    }

    private record MotionScope(BubbleMotionContext context) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return switch (dottedName) {
                case "t" -> context.t();
                case "remaining" -> 1.0D - context.t();
                case "ageMs" -> context.ageMs();
                case "lifetimeMs" -> context.lifetimeMs();
                case "stackIndex" -> (double) context.stackIndex();
                case "stackCount" -> (double) context.stackCount();
                case "lineCount" -> (double) context.lineCount();
                case "stackY" -> context.stackY();
                case "seed" -> context.seed();
                case "pi" -> Math.PI;
                default -> null;
            };
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }
}
