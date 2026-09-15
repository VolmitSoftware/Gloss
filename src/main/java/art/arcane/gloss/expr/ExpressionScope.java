package art.arcane.gloss.expr;

import java.util.List;

public interface ExpressionScope {
    Object variable(String name);

    Object call(String name, List<Object> arguments);
}
