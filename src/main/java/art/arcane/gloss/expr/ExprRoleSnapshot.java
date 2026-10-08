package art.arcane.gloss.expr;

import java.util.List;

public interface ExprRoleSnapshot {
    Object variable(String property);

    Object call(String name, List<Object> arguments);

    boolean ownsCurrentThread();
}
