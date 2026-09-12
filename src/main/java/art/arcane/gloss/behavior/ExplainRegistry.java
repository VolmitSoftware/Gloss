package art.arcane.gloss.behavior;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The kinds {@code /gloss explain} can ask. Each runtime registers itself once; re-registering a
 * kind replaces it so a reload never leaves a stale runtime behind.
 */
public final class ExplainRegistry {
    private static final ExplainRegistry GLOBAL = new ExplainRegistry();

    private final ConcurrentMap<String, Explainable> explainables = new ConcurrentHashMap<>();

    public static ExplainRegistry global() {
        return GLOBAL;
    }

    public void register(String kind, Explainable explainable) {
        explainables.put(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(explainable, "explainable"));
    }

    public void unregister(String kind) {
        explainables.remove(Objects.requireNonNull(kind, "kind"));
    }

    public Explainable find(String kind) {
        return kind == null ? null : explainables.get(kind);
    }

    /** Every registered kind, sorted, for the command's tab completion and its unknown-kind message. */
    public List<String> kinds() {
        return explainables.keySet().stream().sorted().toList();
    }
}
