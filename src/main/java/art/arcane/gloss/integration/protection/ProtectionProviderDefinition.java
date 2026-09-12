package art.arcane.gloss.integration.protection;

import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** One claim plugin Gloss knows how to ask: its plugin name and how to build its adapter. */
record ProtectionProviderDefinition(String pluginName, ProviderFactory factory) {
    ProtectionProviderDefinition {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(factory, "factory");
    }

    @FunctionalInterface
    interface ProviderFactory {
        ContainerProtectionProvider create(Plugin plugin) throws ReflectiveOperationException;
    }
}
