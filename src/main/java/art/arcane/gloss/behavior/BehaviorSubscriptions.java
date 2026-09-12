package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.state.StateConflictException;
import art.arcane.gloss.state.StateDeclarations;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateStore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The compiled view of every loaded behavior document: the state schema the enabled documents
 * declare (a conflicting document is refused as a whole, in id order, so the first declaration
 * wins deterministically) and one subscription list per trigger. A document is checked against the
 * plugin and scene declarations too, so a collision with a running scene's {@code once} key refuses
 * that document rather than aborting the reload and leaving the subscriptions stale.
 */
public final class BehaviorSubscriptions {
    private static final BehaviorSubscriptions EMPTY = new BehaviorSubscriptions(List.of(), Map.of(), Map.of());

    public record Subscription(BehaviorRuntime runtime, BehaviorRuntime.CompiledEntry entry) {
    }

    private final List<BehaviorRuntime> runtimes;
    private final Map<BehaviorTrigger, List<Subscription>> byTrigger;
    private final Map<String, String> refused;

    private BehaviorSubscriptions(List<BehaviorRuntime> runtimes, Map<BehaviorTrigger, List<Subscription>> byTrigger,
                                  Map<String, String> refused) {
        this.runtimes = List.copyOf(runtimes);
        this.byTrigger = Map.copyOf(byTrigger);
        this.refused = Map.copyOf(refused);
    }

    public static BehaviorSubscriptions empty() {
        return EMPTY;
    }

    public static BehaviorSubscriptions compile(Map<String, BehaviorDoc> documents, StateStore store) {
        List<String> ids = new ArrayList<>(documents.keySet());
        ids.sort(String::compareTo);
        Map<String, List<StateSchema>> external = store == null
            ? Map.of() : store.externalDeclarations();
        Map<String, List<StateSchema>> accepted = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        List<BehaviorRuntime> runtimes = new ArrayList<>(ids.size());
        Map<BehaviorTrigger, List<Subscription>> byTrigger = new EnumMap<>(BehaviorTrigger.class);
        for (String id : ids) {
            BehaviorDoc doc = documents.get(id);
            if (doc.enabled()) {
                Map<String, List<StateSchema>> candidate = new LinkedHashMap<>(external);
                candidate.putAll(accepted);
                candidate.put(id, doc.stateSchemas());
                try {
                    StateDeclarations.merge(candidate);
                } catch (StateConflictException conflict) {
                    refused.put(id, conflict.getMessage());
                    continue;
                }
                accepted.put(id, doc.stateSchemas());
            }
            BehaviorRuntime runtime;
            try {
                runtime = BehaviorRuntime.compile(id, doc);
            } catch (RuntimeException invalid) {
                refused.put(id, invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage());
                accepted.remove(id);
                continue;
            }
            runtimes.add(runtime);
            if (!doc.enabled()) {
                continue;
            }
            for (BehaviorRuntime.CompiledEntry entry : runtime.entries()) {
                byTrigger.computeIfAbsent(entry.entry().trigger(), trigger -> new ArrayList<>())
                    .add(new Subscription(runtime, entry));
            }
        }
        if (store != null) {
            try {
                store.declare(accepted);
            } catch (StateConflictException conflict) {
                Gloss.log(Level.WARNING,
                    "Behavior state declarations were left unchanged: %s", conflict.getMessage());
            }
        }
        Map<BehaviorTrigger, List<Subscription>> frozen = new EnumMap<>(BehaviorTrigger.class);
        for (Map.Entry<BehaviorTrigger, List<Subscription>> entry : byTrigger.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new BehaviorSubscriptions(runtimes, frozen, refused);
    }

    public List<Subscription> subscribed(BehaviorTrigger trigger) {
        return byTrigger.getOrDefault(trigger, List.of());
    }

    public boolean hasAny(BehaviorTrigger trigger) {
        return !subscribed(trigger).isEmpty();
    }

    public List<BehaviorRuntime> runtimes() {
        return runtimes;
    }

    public BehaviorRuntime runtime(String id) {
        for (BehaviorRuntime runtime : runtimes) {
            if (runtime.id().equals(id)) {
                return runtime;
            }
        }
        return null;
    }

    /** Document id to the reason it was refused (a state conflict or a compile failure). */
    public Map<String, String> refused() {
        return refused;
    }
}
