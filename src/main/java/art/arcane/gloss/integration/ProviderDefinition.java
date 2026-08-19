package art.arcane.gloss.integration;

/**
 * A known adapter. The class name is a string on purpose: the adapter references its host plugin's
 * API types, so it must not be loaded before that plugin is confirmed present.
 */
public record ProviderDefinition(String pluginName, String className) {
}
