package art.arcane.gloss.condition;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public record ConditionReferences(Set<String> variables, Set<String> functions, Set<String> metricKeys) {

  public ConditionReferences {
    variables = immutableSorted(variables);
    functions = immutableSorted(functions);
    metricKeys = immutableSorted(metricKeys);
  }

  private static Set<String> immutableSorted(Set<String> values) {
    return Collections.unmodifiableSet(new TreeSet<String>(values));
  }
}
