package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.RigStateActionData;
import art.arcane.gloss.rig.RigInstance;
import art.arcane.gloss.rig.RigService;

public final class RigStateMenuAction extends MenuAction<RigStateActionData> {
    public RigStateMenuAction(RigStateActionData data) {
        super(data);
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        RigInstance instance = RigTargets.resolve(context, data.instanceOrSelf());
        RigService service = RigTargets.service();
        if (instance != null && service != null) {
            service.setState(instance.id(), data.state().trim());
        }
        return ActionOutcome.CONTINUE;
    }
}
