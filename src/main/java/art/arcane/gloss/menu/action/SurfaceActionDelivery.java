package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.surface.SurfaceDelivery;
import art.arcane.gloss.surface.SurfaceService;

/** Where the three surface actions send their output, and how they name themselves on the compositor. */
final class SurfaceActionDelivery {
    static final String PURPOSE_PREFIX = "gloss:action:";

    private SurfaceActionDelivery() {
    }

    static SurfaceDelivery resolve(SurfaceDelivery injected) {
        if (injected != null) {
            return injected;
        }
        SurfaceService service = Gloss.instance == null ? null : Gloss.instance.service(SurfaceService.class);
        return service == null ? null : service.delivery();
    }

    static String purpose(ActionContext context) {
        return PURPOSE_PREFIX + context.menuId() + "/" + context.componentId();
    }
}
