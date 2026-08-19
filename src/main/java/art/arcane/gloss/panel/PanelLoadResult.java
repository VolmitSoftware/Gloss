package art.arcane.gloss.panel;

import java.util.Map;

public record PanelLoadResult(int loaded, int retained, int removed, Map<String, String> failures) {
  public PanelLoadResult {
    if (loaded < 0 || retained < 0 || removed < 0) {
      throw new IllegalArgumentException("load counts must not be negative");
    }
    failures = Map.copyOf(failures);
  }

  public boolean successful() {
    return failures.isEmpty();
  }
}
