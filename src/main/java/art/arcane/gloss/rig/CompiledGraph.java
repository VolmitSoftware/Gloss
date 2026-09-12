package art.arcane.gloss.rig;

import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CompiledGraph(String initial, Map<String, CompiledState> states, List<CompiledTransition> transitions) {
    public static final String ANY_STATE = "*";
    private static final CompiledGraph EMPTY = new CompiledGraph(null, Map.of(), List.of());

    public static CompiledGraph compile(RigDoc.Graph graph) {
        if (graph == null) {
            return EMPTY;
        }
        Map<String, CompiledState> states = new LinkedHashMap<>(graph.states().size() * 2);
        for (Map.Entry<String, RigDoc.State> entry : graph.states().entrySet()) {
            states.put(entry.getKey(), new CompiledState(entry.getKey(), entry.getValue().clip(), entry.getValue().then()));
        }
        List<CompiledTransition> transitions = new ArrayList<>(graph.transitions().size());
        for (RigDoc.Transition transition : graph.transitions()) {
            transitions.add(new CompiledTransition(transition.from(), transition.to(), ConditionCompiler.compile(
                new ConditionSource("graph.transitions[" + transition.from() + "->" + transition.to() + "].when",
                    transition.when()))));
        }
        return new CompiledGraph(graph.initial(), Map.copyOf(states), List.copyOf(transitions));
    }

    public boolean isEmpty() {
        return initial == null;
    }

    public CompiledState state(String id) {
        return id == null ? null : states.get(id);
    }

    public record CompiledState(String id, String clip, String then) {
    }

    public record CompiledTransition(String from, String to, CompiledCondition condition) {
        boolean appliesFrom(String state) {
            return from.equals(ANY_STATE) || from.equals(state);
        }
    }
}
