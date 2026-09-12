package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.SetRigActionData;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.rig.RigActionContext;
import art.arcane.gloss.rig.RigInstance;
import art.arcane.gloss.rig.RigNamespace;
import art.arcane.gloss.rig.RigService;

public final class SetRigMenuAction extends MenuAction<SetRigActionData> {
    private final Expr value;

    public SetRigMenuAction(SetRigActionData data) {
        super(data);
        Expr parsed;
        try {
            parsed = data.parseValue();
        } catch (RuntimeException failure) {
            parsed = null;
        }
        this.value = parsed;
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        RigInstance instance = RigTargets.resolve(context, data.instanceOrSelf());
        if (instance == null || value == null) {
            return ActionOutcome.CONTINUE;
        }
        Object evaluated;
        try {
            evaluated = RigNamespace.with(instance.machine(), () -> ExprEvaluator.eval(value, context.conditionScope()));
        } catch (RuntimeException failure) {
            Gloss.warnThrottled("rig-setvar-" + instance.id() + "-" + data.var(),
                "setRig %s.%s failed: %s", instance.id(), data.var(), failure.getMessage());
            return ActionOutcome.CONTINUE;
        }
        RigService service = RigTargets.service();
        if (service != null) {
            service.setVar(instance.id(), data.var().trim(), evaluated);
        }
        return ActionOutcome.CONTINUE;
    }
}
