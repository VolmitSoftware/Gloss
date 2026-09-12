package art.arcane.gloss.lint;

import java.util.List;

/** One check over the workspace. Rules are pure over {@link LintContext} and hold no state. */
public interface LintRule {
    String code();

    List<Diagnostic> check(LintContext context);
}
