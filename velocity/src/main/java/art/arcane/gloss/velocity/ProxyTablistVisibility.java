package art.arcane.gloss.velocity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ProxyTablistVisibility(Map<UUID, Set<UUID>> hidden, Set<UUID> npcs, Set<UUID> bedrock) {
    public static final ProxyTablistVisibility EMPTY = new ProxyTablistVisibility(Map.of(), Set.of(), Set.of());

    public ProxyTablistVisibility {
        Map<UUID, Set<UUID>> copied = new HashMap<>(hidden.size());
        hidden.forEach((viewer, subjects) -> copied.put(viewer, Set.copyOf(subjects)));
        hidden = Map.copyOf(copied);
        npcs = Set.copyOf(npcs);
        bedrock = Set.copyOf(bedrock);
    }

    public boolean visible(UUID viewer, UUID subject) {
        return !hidden.getOrDefault(viewer, Set.of()).contains(subject);
    }
}
