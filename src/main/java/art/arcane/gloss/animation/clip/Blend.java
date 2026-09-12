package art.arcane.gloss.animation.clip;

import java.util.Locale;

public enum Blend {
    ADD,
    REPLACE,
    MULTIPLY;

    public static Blend parse(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
