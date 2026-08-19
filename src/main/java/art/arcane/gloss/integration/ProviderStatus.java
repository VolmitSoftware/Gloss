package art.arcane.gloss.integration;

/**
 * One row of the provider status report. {@code itemCount} is 0 unless the provider is ready, and
 * enumerating it is not free (HeadDatabase exposes tens of thousands of ids), so this is built on
 * demand for the command and never on a menu path.
 */
public record ProviderStatus(String id, String pluginName, boolean pluginPresent, boolean active, boolean ready, int itemCount) {
}
