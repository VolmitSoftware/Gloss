package art.arcane.gloss.api;

import java.util.Optional;

/** Static access to the {@link GlossStateProvider}; empty while Gloss is not enabled. */
public final class GlossStateAccess {
    private static volatile GlossStateProvider provider;

    private GlossStateAccess() {
    }

    public static Optional<GlossStateProvider> get() {
        return Optional.ofNullable(provider);
    }

    public static void set(GlossStateProvider value) {
        provider = value;
    }
}
