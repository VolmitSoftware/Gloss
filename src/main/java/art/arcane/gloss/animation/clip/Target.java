package art.arcane.gloss.animation.clip;

import java.util.Locale;

public enum Target {
    OFFSET_X(0),
    OFFSET_Y(1),
    OFFSET_Z(2),
    ROTATION_X(3),
    ROTATION_Y(4),
    ROTATION_Z(5),
    SCALE_X(6),
    SCALE_Y(7),
    SCALE_Z(8),
    GLOW(9),
    VISIBLE(10),
    PHYSICS(11),
    LIGHT_LEVEL(12),
    TRANSLATION_X(0),
    TRANSLATION_Y(1),
    TRANSLATION_Z(2),
    OPACITY(13),
    BRIGHTNESS(14);

    private final int channel;

    Target(int channel) {
        this.channel = channel;
    }

    public static Target parse(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }

    public int channel() {
        return channel;
    }

    public boolean isTranslation() {
        return channel <= OFFSET_Z.channel;
    }

    public boolean isRotation() {
        return channel >= ROTATION_X.channel && channel <= ROTATION_Z.channel;
    }

    public boolean isScale() {
        return channel >= SCALE_X.channel && channel <= SCALE_Z.channel;
    }
}
