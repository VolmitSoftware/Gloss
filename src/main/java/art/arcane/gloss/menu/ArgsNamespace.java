package art.arcane.gloss.menu;

import art.arcane.gloss.behavior.ArgsView;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;

import java.util.Map;

/**
 * The {@code args.<name>} namespace: what the open surface was opened with, or - while a behavior
 * program is running on this thread - what its trigger carried. Arguments are seeded as session
 * variables, but they stay readable on their own so an author can tell a value that came from the
 * opener apart from one a click has since written.
 */
public final class ArgsNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "args";

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        Map<String, Object> program = ArgsView.current();
        if (program != null && program.containsKey(suffix)) {
            return program.get(suffix);
        }
        SessionVariables variables = SessionNamespace.variablesOf(context);
        return variables == null ? null : variables.args().get(suffix);
    }
}
