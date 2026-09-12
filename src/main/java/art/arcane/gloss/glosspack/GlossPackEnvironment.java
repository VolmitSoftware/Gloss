package art.arcane.gloss.glosspack;

import java.util.Objects;
import java.util.Set;

/** What a pack is checked against: this build's version, what else is installed, and the policy. */
public record GlossPackEnvironment(String glossVersion, Set<String> installedPlugins,
                                   boolean allowServerCommands) {
    public GlossPackEnvironment {
        glossVersion = Objects.requireNonNull(glossVersion, "glossVersion");
        installedPlugins = Set.copyOf(installedPlugins);
    }
}
