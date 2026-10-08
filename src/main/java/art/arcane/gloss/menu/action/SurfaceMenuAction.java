package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.behavior.BehaviorActionContext;
import art.arcane.gloss.config.action.SurfaceActionData;
import art.arcane.gloss.surface.SurfaceService;

public final class SurfaceMenuAction extends MenuAction<SurfaceActionData> {
    public SurfaceMenuAction(SurfaceActionData data) {
        super(data);
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        if (context instanceof BehaviorActionContext behavior && !behavior.currentDefinition()) {
            return ActionOutcome.CONTINUE;
        }
        SurfaceService service = Gloss.instance == null ? null : Gloss.instance.service(SurfaceService.class);
        if (service != null) {
            service.submit(data, context.player());
        }
        return ActionOutcome.CONTINUE;
    }

    @Override
    protected boolean requiresPlayer() {
        return false;
    }
}
