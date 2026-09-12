package art.arcane.gloss.forge;

import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprFunctionRegistry.Kind;
import art.arcane.gloss.expr.ExprFunctionRegistry.Spec;
import art.arcane.gloss.expr.ExprScope;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pixel-width expression functions over the vanilla font table. Padding uses spaces rounded to
 * four pixels for every viewer, and exact negative-space glyphs when the scope's viewer has the
 * Gloss pack loaded ({@code pack.loaded}).
 */
public final class PixelFunctions {
    public static final List<String> NAMES = List.of("px", "pxWidth", "pxPad", "pxAlign", "column");
    static final int SPACE_WIDTH = 4;
    private static final int NO_POSITION = -1;
    private static final int MAX_WIDTH = 16384;

    private final FontMetrics metrics;
    private final Supplier<SpaceGlyphs> spaces;

    public PixelFunctions(FontMetrics metrics, Supplier<SpaceGlyphs> spaces) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.spaces = Objects.requireNonNull(spaces, "spaces");
    }

    public List<Spec> specs() {
        return List.of(
            new Spec("px", Kind.NUMBER, List.of(Kind.STRING), false, (scope, args) -> (double) width("px", args)),
            new Spec("pxWidth", Kind.NUMBER, List.of(Kind.STRING), false, (scope, args) -> (double) width("pxWidth", args)),
            new Spec("pxPad", Kind.STRING, List.of(Kind.STRING, Kind.NUMBER), false, this::pxPad),
            new Spec("pxAlign", Kind.STRING, List.of(Kind.STRING, Kind.NUMBER, Kind.STRING), false, this::pxAlign),
            new Spec("column", Kind.STRING, List.of(Kind.LIST, Kind.LIST, Kind.LIST), false, this::column));
    }

    public void register(ExprFunctionRegistry registry) {
        for (Spec spec : specs()) {
            registry.register(spec);
        }
    }

    public int width(String text) {
        return metrics.width(text);
    }

    static boolean packLoaded(ExprScope scope) {
        try {
            return Boolean.TRUE.equals(scope.variable("pack.loaded"));
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    private int width(String name, List<Object> args) {
        return metrics.width(strArg(name, args, 0));
    }

    private String pxPad(ExprScope scope, List<Object> args) {
        String text = strArg("pxPad", args, 0);
        int target = widthArg("pxPad", args, 1);
        return text + pad(scope, target - metrics.width(text));
    }

    private String pxAlign(ExprScope scope, List<Object> args) {
        return align(scope, strArg("pxAlign", args, 0), widthArg("pxAlign", args, 1), strArg("pxAlign", args, 2));
    }

    private String align(ExprScope scope, String text, int target, String mode) {
        int remaining = target - metrics.width(text);
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "left" -> text + pad(scope, remaining);
            case "right" -> pad(scope, remaining) + text;
            case "center", "middle" -> pad(scope, remaining / 2) + text + pad(scope, remaining - remaining / 2);
            default -> throw new ExprException("pxAlign mode must be 'left', 'center' or 'right': " + mode, NO_POSITION);
        };
    }

    private String column(ExprScope scope, List<Object> args) {
        List<?> texts = listArg("column", args, 0);
        List<?> widths = listArg("column", args, 1);
        List<?> aligns = listArg("column", args, 2);
        if (widths.size() < texts.size()) {
            throw new ExprException("column needs one width per cell: " + texts.size() + " cells, "
                + widths.size() + " widths", NO_POSITION);
        }
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < texts.size(); index++) {
            String text = text("column", texts.get(index), index);
            Object width = widths.get(index);
            if (!(width instanceof Double value)) {
                throw new ExprException("column width " + (index + 1) + " must be a number", NO_POSITION);
            }
            String align = index < aligns.size() ? text("column", aligns.get(index), index) : "left";
            row.append(align(scope, text, requireWidth("column", value), align));
        }
        return row.toString();
    }

    private String pad(ExprScope scope, int px) {
        if (px <= 0) {
            return "";
        }
        if (packLoaded(scope)) {
            String exact = spaces.get().shift(px);
            if (!exact.isEmpty()) {
                return exact;
            }
        }
        return " ".repeat((int) Math.round(px / (double) SPACE_WIDTH));
    }

    /** Cell values arrive already evaluated; the expression language's own string coercion rules apply. */
    private static String text(String name, Object value, int index) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) && number == Math.rint(number)
                ? Long.toString((long) (double) number) : Double.toString(number);
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        throw new ExprException(name + " cell " + (index + 1) + " must be a string, number or boolean", NO_POSITION);
    }

    private static int widthArg(String name, List<Object> args, int index) {
        Object value = args.get(index);
        if (!(value instanceof Double number)) {
            throw new ExprException(name + " argument " + (index + 1) + " must be a number", NO_POSITION);
        }
        return requireWidth(name, number);
    }

    private static int requireWidth(String name, double value) {
        if (value != Math.rint(value) || value < 0 || value > MAX_WIDTH) {
            throw new ExprException(name + " width must be a whole number in [0, " + MAX_WIDTH + "]", NO_POSITION);
        }
        return (int) value;
    }

    private static String strArg(String name, List<Object> args, int index) {
        Object value = args.get(index);
        if (value instanceof String text) {
            return text;
        }
        throw new ExprException(name + " argument " + (index + 1) + " must be a string", NO_POSITION);
    }

    private static List<?> listArg(String name, List<Object> args, int index) {
        Object value = args.get(index);
        if (value instanceof List<?> list) {
            return list;
        }
        throw new ExprException(name + " argument " + (index + 1) + " must be a list", NO_POSITION);
    }
}
