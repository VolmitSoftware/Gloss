package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.SetRigActionData;
import art.arcane.gloss.rig.RigActionContext;
import art.arcane.gloss.rig.RigInstance;
import art.arcane.gloss.rig.RigService;

final class RigTargets {
    private RigTargets() {
    }

    static RigService service() {
        Gloss plugin = Gloss.instance;
        return plugin == null ? null : plugin.service(RigService.class);
    }

    static RigInstance resolve(ActionContext context, String instanceId) {
        if (SetRigActionData.SELF.equals(instanceId)) {
            return context instanceof RigActionContext rig ? rig.instance() : null;
        }
        RigService service = service();
        return service == null ? null : service.instance(instanceId).orElse(null);
    }
}
