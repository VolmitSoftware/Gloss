package art.arcane.gloss.marker;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;

/**
 * Resolves {@code marker.*} inside a marker's own label, {@code show}, {@code audience} and
 * {@code distanceScale} expressions. The current marker is a thread local because the renderer
 * evaluates one marker at a time on the viewer's region thread.
 */
public final class MarkerNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "marker";

    private static final ThreadLocal<MarkerContext> CURRENT = new ThreadLocal<>();

    public static void push(MarkerContext context) {
        CURRENT.set(context);
    }

    public static void pop() {
        CURRENT.remove();
    }

    public static MarkerContext current() {
        return CURRENT.get();
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        MarkerContext marker = CURRENT.get();
        if (marker == null) {
            return null;
        }
        return switch (suffix) {
            case "id" -> marker.id();
            case "x" -> marker.x();
            case "y" -> marker.y();
            case "z" -> marker.z();
            case "distance" -> marker.distance();
            default -> null;
        };
    }
}
