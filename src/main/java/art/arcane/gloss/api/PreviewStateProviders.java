package art.arcane.gloss.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of third-party {@link PreviewStateProvider}s. Registration is process-wide and lives
 * for the lifetime of the JVM, so plugins should unregister on disable.
 *
 * <p>Backed by a copy-on-write list: registration is rare, iteration happens on region threads
 * during every preview snapshot.
 */
public final class PreviewStateProviders {

  private static final CopyOnWriteArrayList<PreviewStateProvider> PROVIDERS = new CopyOnWriteArrayList<>();

  private PreviewStateProviders() {
  }

  /** Registers a provider. Registering the same instance twice is a no-op. */
  public static void register(PreviewStateProvider provider) {
    Objects.requireNonNull(provider, "provider");
    PROVIDERS.addIfAbsent(provider);
  }

  /** Removes a previously registered provider. Unknown instances are ignored. */
  public static void unregister(PreviewStateProvider provider) {
    if (provider != null) {
      PROVIDERS.remove(provider);
    }
  }

  /** Every registered provider, in registration order. */
  public static List<PreviewStateProvider> all() {
    return Collections.unmodifiableList(PROVIDERS);
  }
}
