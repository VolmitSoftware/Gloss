package art.arcane.gloss.rig;

import art.arcane.gloss.expr.ExprScope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RigStateMachine implements RigScope {
    private final String instanceId;
    private final CompiledGraph graph;
    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile String state;

    public RigStateMachine(String instanceId, CompiledGraph graph, Map<String, Object> initialVars) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.state = graph.initial();
        if (initialVars != null) {
            for (Map.Entry<String, Object> entry : initialVars.entrySet()) {
                Object value = normalize(entry.getValue());
                if (value != null) {
                    vars.put(entry.getKey(), value);
                }
            }
        }
    }

    public static Object normalize(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        return null;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public String state() {
        return state;
    }

    @Override
    public Object var(String name) {
        return name == null ? null : vars.get(name);
    }

    public String clip() {
        CompiledGraph.CompiledState current = graph.state(state);
        return current == null ? null : current.clip();
    }

    public Map<String, Object> vars() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    public long generation() {
        return generation.get();
    }

    public boolean hasState(String name) {
        return graph.state(name) != null;
    }

    public void setVar(String name, Object value) {
        Objects.requireNonNull(name, "name");
        Object normalized = normalize(value);
        Object previous = normalized == null ? vars.remove(name) : vars.put(name, normalized);
        if (!Objects.equals(previous, normalized)) {
            generation.incrementAndGet();
        }
    }

    public boolean setState(String name) {
        if (graph.state(name) == null) {
            return false;
        }
        if (!name.equals(state)) {
            state = name;
            generation.incrementAndGet();
        }
        return true;
    }

    public boolean step(ExprScope scope, boolean clipFinished) {
        if (graph.isEmpty()) {
            return false;
        }
        String current = state;
        String next = RigNamespace.with(this, () -> matchingTransition(scope, current));
        if (next == null && clipFinished) {
            CompiledGraph.CompiledState currentState = graph.state(current);
            next = currentState == null ? null : currentState.then();
        }
        if (next == null || next.equals(current) || graph.state(next) == null) {
            return false;
        }
        state = next;
        generation.incrementAndGet();
        return true;
    }

    private String matchingTransition(ExprScope scope, String current) {
        for (CompiledGraph.CompiledTransition transition : graph.transitions()) {
            if (!transition.appliesFrom(current) || transition.to().equals(current)) {
                continue;
            }
            if (transition.condition().matches(scope)) {
                return transition.to();
            }
        }
        return null;
    }
}
