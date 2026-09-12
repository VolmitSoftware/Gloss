package art.arcane.gloss.motion;

import art.arcane.gloss.animation.clip.Blend;
import art.arcane.gloss.animation.clip.Target;

import java.util.Locale;

public enum MotionChannel {
    TRANSLATION_X("translation.x", Target.TRANSLATION_X, Blend.ADD, ValueKind.NUMBER),
    TRANSLATION_Y("translation.y", Target.TRANSLATION_Y, Blend.ADD, ValueKind.NUMBER),
    TRANSLATION_Z("translation.z", Target.TRANSLATION_Z, Blend.ADD, ValueKind.NUMBER),
    ROTATION_X("rotation.x", Target.ROTATION_X, Blend.ADD, ValueKind.NUMBER),
    ROTATION_Y("rotation.y", Target.ROTATION_Y, Blend.ADD, ValueKind.NUMBER),
    ROTATION_Z("rotation.z", Target.ROTATION_Z, Blend.ADD, ValueKind.NUMBER),
    SCALE_X("scale.x", Target.SCALE_X, Blend.MULTIPLY, ValueKind.NUMBER),
    SCALE_Y("scale.y", Target.SCALE_Y, Blend.MULTIPLY, ValueKind.NUMBER),
    SCALE_Z("scale.z", Target.SCALE_Z, Blend.MULTIPLY, ValueKind.NUMBER),
    GLOW("glow", Target.GLOW, Blend.REPLACE, ValueKind.COLOR),
    VISIBLE("visible", Target.VISIBLE, Blend.REPLACE, ValueKind.BOOLEAN),
    OPACITY("opacity", Target.OPACITY, Blend.REPLACE, ValueKind.NUMBER),
    BRIGHTNESS("brightness", Target.BRIGHTNESS, Blend.REPLACE, ValueKind.NUMBER);

    private final String name;
    private final Target target;
    private final Blend defaultBlend;
    private final ValueKind valueKind;

    MotionChannel(String name, Target target, Blend defaultBlend, ValueKind valueKind) {
        this.name = name;
        this.target = target;
        this.defaultBlend = defaultBlend;
        this.valueKind = valueKind;
    }

    public static MotionChannel parse(String source) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        for (MotionChannel channel : values()) {
            if (channel.name.equals(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("unknown motion channel '" + source + "'");
    }

    public String channelName() {
        return name;
    }

    public Target target() {
        return target;
    }

    public Blend defaultBlend() {
        return defaultBlend;
    }

    public double value(Object raw) {
        return switch (valueKind) {
            case NUMBER -> {
                if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw new IllegalArgumentException("motion channel " + name + " needs a finite number, got " + describe(raw));
                }
                yield number.doubleValue();
            }
            case BOOLEAN -> {
                if (!(raw instanceof Boolean flag)) {
                    throw new IllegalArgumentException("motion channel " + name + " needs true or false, got " + describe(raw));
                }
                yield flag ? 1.0D : 0.0D;
            }
            case COLOR -> {
                if (raw instanceof Number number) {
                    yield number.doubleValue();
                }
                if (raw instanceof String text) {
                    yield parseColor(text);
                }
                throw new IllegalArgumentException("motion channel " + name + " needs a #RRGGBB or #AARRGGBB color, got " + describe(raw));
            }
        };
    }

    private static double parseColor(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("#") || (trimmed.length() != 7 && trimmed.length() != 9)) {
            throw new IllegalArgumentException("motion color must be #RRGGBB or #AARRGGBB, got '" + text + "'");
        }
        long parsed;
        try {
            parsed = Long.parseLong(trimmed.substring(1), 16);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("motion color must be #RRGGBB or #AARRGGBB, got '" + text + "'", failure);
        }
        if (trimmed.length() == 7) {
            parsed |= 0xFF000000L;
        }
        return (double) parsed;
    }

    private static String describe(Object raw) {
        return raw == null ? "nothing" : raw.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private enum ValueKind {
        NUMBER,
        BOOLEAN,
        COLOR
    }
}
