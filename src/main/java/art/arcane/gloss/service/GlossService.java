package art.arcane.gloss.service;

import art.arcane.gloss.GlossConfig;

/**
 * Lifecycle contract for a feature service that a headline lane adds to {@link art.arcane.gloss.Gloss}.
 *
 * <p>Services are constructed by {@link GlossLaneServices#create} right after {@code gloss.toml} is
 * loaded and before the text pipeline exists, so a constructor may read {@code plugin.cfg()} and the
 * data folder but must not touch any other service. {@link #contribute()} runs next and may only
 * populate registries (expression functions, variable namespaces). {@link #enable()} runs after every
 * core service is up, in lane order, and {@link #disable()} runs in reverse on shutdown.
 */
public interface GlossService {
    String name();

    default void contribute() {
    }

    void enable();

    void disable();

    default void reload() {
    }

    default boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return false;
    }
}
