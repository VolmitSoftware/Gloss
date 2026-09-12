package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.SkyActionData;
import art.arcane.gloss.sky.SkyService;

public final class SkyMenuAction extends MenuAction<SkyActionData> {
    public SkyMenuAction(SkyActionData data) {
        super(data);
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        SkyService sky = Gloss.instance == null ? null : Gloss.instance.service(SkyService.class);
        if (sky == null || !sky.enabled()) {
            return ActionOutcome.CONTINUE;
        }
        if (data.releases()) {
            sky.release(context.player(), data.purpose());
        } else {
            sky.apply(context.player(), data.toOverride());
        }
        return ActionOutcome.CONTINUE;
    }
}
