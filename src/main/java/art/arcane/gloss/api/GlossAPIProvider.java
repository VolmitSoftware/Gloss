package art.arcane.gloss.api;

import java.util.Objects;

public final class GlossAPIProvider {
    private static volatile GlossAPI api;

    private GlossAPIProvider() {
    }

    public static GlossAPI get() {
        return Objects.requireNonNull(api, "Gloss is not enabled");
    }

    public static void set(GlossAPI value) {
        api = value;
    }
}
