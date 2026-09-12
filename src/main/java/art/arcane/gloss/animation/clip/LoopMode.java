package art.arcane.gloss.animation.clip;

import java.util.Locale;

public enum LoopMode {
    ONCE,
    LOOP,
    PINGPONG;

    public static LoopMode parse(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
