package art.arcane.gloss.doc;

import java.util.Map;

/**
 * A service that owns document registries and lets workspace-wide passes read them by collection
 * name. History records what changed in them; the workspace linter reads them to check references
 * across kinds. Implementing this is the only thing a service has to do to join both.
 */
public interface RegistryOwner {
    /** This service's registries keyed by the data-folder collection they read. */
    Map<String, DocumentRegistry<?>> registries();
}
