package art.arcane.gloss.api;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.marker.MarkerSpec;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of third-party {@link MarkerProvider}s. Registration is process-wide; plugins
 * unregister on disable. A provider that throws is dropped from the pass and logged once per
 * owner, so one broken plugin never blanks another's markers.
 */
public final class MarkerProviders {
    public record Registration(Plugin owner, MarkerProvider provider) {
        public Registration {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(provider, "provider");
        }
    }

    private static final CopyOnWriteArrayList<Registration> PROVIDERS = new CopyOnWriteArrayList<>();

    private MarkerProviders() {
    }

    public static void register(Plugin owner, MarkerProvider provider) {
        PROVIDERS.add(new Registration(owner, provider));
    }

    public static void unregister(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        PROVIDERS.removeIf(registration -> registration.owner() == owner);
    }

    public static List<Registration> all() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    public static void clear() {
        PROVIDERS.clear();
    }

    /** Every provider's markers for this viewer, in registration order. */
    public static List<MarkerSpec> collect(Player viewer) {
        List<MarkerSpec> markers = new ArrayList<>();
        for (Registration registration : PROVIDERS) {
            List<MarkerSpec> supplied;
            try {
                supplied = registration.provider().markersFor(viewer);
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "marker-provider:" + registration.owner().getName(),
                    failure, "Marker provider from %s failed; its markers were skipped.",
                    registration.owner().getName());
                continue;
            }
            if (supplied != null) {
                markers.addAll(supplied);
            }
        }
        return List.copyOf(markers);
    }
}
