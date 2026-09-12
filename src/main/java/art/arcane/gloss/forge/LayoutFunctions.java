package art.arcane.gloss.forge;

import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprFunctionRegistry.Kind;
import art.arcane.gloss.expr.ExprFunctionRegistry.Spec;
import art.arcane.gloss.expr.ExprScope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The expression functions that place glyphs on a line. Every one of them renders for viewers that
 * have the pack and degrades to the glyph's {@code fallback} text for everyone else, so one
 * authored string works on a vanilla client, on Bedrock and behind the pack.
 */
public final class LayoutFunctions implements SpaceGlyphs {
    public static final List<String> NAMES = List.of("glyph", "shift", "at", "overlay", "meter");

    private static final int NO_POSITION = -1;
    private static final int MAX_SHIFT = 4096;

    private final Supplier<GlyphRegistry> registry;
    private final FontMetrics metrics;

    public LayoutFunctions(Supplier<GlyphRegistry> registry, FontMetrics metrics) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public List<Spec> specs() {
        return List.of(
            new Spec("glyph", Kind.STRING, List.of(Kind.STRING), false, this::glyph),
            new Spec("overlay", Kind.STRING, List.of(Kind.STRING), false, this::overlay),
            new Spec("shift", Kind.STRING, List.of(Kind.NUMBER), false, this::shift),
            new Spec("at", Kind.STRING, List.of(Kind.NUMBER, Kind.STRING), false, this::at),
            new Spec("meter", Kind.STRING, List.of(Kind.STRING, Kind.NUMBER, Kind.NUMBER), false, this::meter));
    }

    public void register(ExprFunctionRegistry target) {
        for (Spec spec : specs()) {
            target.register(spec);
        }
    }

    /**
     * The space-glyph string for {@code px}, composed from several advances when the declared range
     * is narrower than the shift. Empty when no space provider is built.
     */
    @Override
    public String shift(int px) {
        GlyphRegistry.Spaces spaces = registry.get().spaces();
        if (px == 0 || !spaces.enabled()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int remaining = px;
        int step = remaining > 0 ? spaces.maximum() : spaces.minimum();
        while (step != 0 && Math.abs(remaining) > Math.abs(step)) {
            append(out, spaces.codepoint(step));
            remaining -= step;
        }
        append(out, spaces.codepoint(remaining));
        return out.isEmpty() ? "" : font(out.toString());
    }

    private Object glyph(ExprScope scope, List<Object> args) {
        GlyphRegistry glyphs = registry.get();
        return render(scope, glyphs.glyph(text("glyph", args, 0)));
    }

    private Object overlay(ExprScope scope, List<Object> args) {
        GlyphRegistry glyphs = registry.get();
        return render(scope, glyphs.overlay(text("overlay", args, 0)));
    }

    private Object shift(ExprScope scope, List<Object> args) {
        return PixelFunctions.packLoaded(scope) ? shift(pixels("shift", args, 0)) : "";
    }

    /**
     * Places {@code text} {@code px} pixels from where the line currently sits and shifts back, so
     * several {@code at} calls on one line each measure from the same origin.
     */
    private Object at(ExprScope scope, List<Object> args) {
        int px = pixels("at", args, 0);
        String body = text("at", args, 1);
        if (!PixelFunctions.packLoaded(scope)) {
            return body;
        }
        return shift(px) + body + shift(-(px + metrics.width(body)));
    }

    private Object meter(ExprScope scope, List<Object> args) {
        String id = text("meter", args, 0);
        double value = number("meter", args, 1);
        double max = number("meter", args, 2);
        if (max <= 0) {
            throw new ExprException("meter maximum must be greater than zero: " + max, NO_POSITION);
        }
        Optional<GlyphRegistry.ResolvedGlyph> found = registry.get().glyph(id);
        if (found.isEmpty()) {
            return "";
        }
        GlyphRegistry.ResolvedGlyph glyph = found.get();
        int segments = glyph.frames();
        int filled = (int) Math.round(Math.clamp(value / max, 0.0D, 1.0D) * segments);
        if (!PixelFunctions.packLoaded(scope)) {
            return glyph.fallback().repeat(filled);
        }
        StringBuilder out = new StringBuilder();
        String full = glyph.character(glyph.frames() - 1);
        String empty = glyph.character(0);
        for (int segment = 0; segment < segments; segment++) {
            out.append(segment < filled ? full : empty);
        }
        return font(out.toString());
    }

    /** The font-tagged character for a glyph id, for callers outside the expression language. */
    public Optional<String> fontTag(String glyphId) {
        return registry.get().glyph(glyphId).map(glyph -> font(glyph.character()));
    }

    private Object render(ExprScope scope, Optional<GlyphRegistry.ResolvedGlyph> found) {
        if (found.isEmpty()) {
            return "";
        }
        GlyphRegistry.ResolvedGlyph glyph = found.get();
        return PixelFunctions.packLoaded(scope) ? font(glyph.character()) : glyph.fallback();
    }

    private String font(String characters) {
        GlyphRegistry glyphs = registry.get();
        return "<font:" + glyphs.namespace() + ":" + glyphs.font() + ">" + characters + "</font>";
    }

    private static void append(StringBuilder out, Integer codepoint) {
        if (codepoint != null) {
            out.appendCodePoint(codepoint);
        }
    }

    private static String text(String name, List<Object> args, int index) {
        Object value = args.get(index);
        if (value instanceof String string) {
            return string;
        }
        throw new ExprException(name + " argument " + (index + 1) + " must be a string", NO_POSITION);
    }

    private static double number(String name, List<Object> args, int index) {
        Object value = args.get(index);
        if (value instanceof Double number && Double.isFinite(number)) {
            return number;
        }
        throw new ExprException(name + " argument " + (index + 1) + " must be a number", NO_POSITION);
    }

    private static int pixels(String name, List<Object> args, int index) {
        double value = number(name, args, index);
        if (value != Math.rint(value) || Math.abs(value) > MAX_SHIFT) {
            throw new ExprException(name + " pixels must be a whole number within " + MAX_SHIFT, NO_POSITION);
        }
        return (int) value;
    }
}
