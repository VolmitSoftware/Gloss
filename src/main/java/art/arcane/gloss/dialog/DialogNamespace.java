package art.arcane.gloss.dialog;

import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.expr.ExprVariableNamespace;

/** The {@code dialog.id} namespace: which document the running action list came from. */
public final class DialogNamespace implements ExprVariableNamespace {
    public static final String PREFIX = "dialog";

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public Object resolve(String suffix, ExprVariableContext context) {
        InputNamespace.Binding binding = InputNamespace.active();
        if (binding == null || !suffix.equals("id")) {
            return null;
        }
        return binding.dialogId();
    }
}
