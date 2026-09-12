package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.camera.CameraService;
import art.arcane.gloss.config.action.CameraActionData;

public final class CameraMenuAction extends MenuAction<CameraActionData> {
    public CameraMenuAction(CameraActionData data) {
        super(data);
    }

    @Override
    public ActionOutcome execute(ActionContext context) {
        CameraService camera = Gloss.instance == null ? null : Gloss.instance.service(CameraService.class);
        if (camera == null) {
            return ActionOutcome.CONTINUE;
        }
        camera.ride(context.player(), data.nodes(),
            new CameraService.Options(data.skippableOrDefault(), data.letterboxOrDefault()));
        return terminal() ? ActionOutcome.STOP : ActionOutcome.CONTINUE;
    }

    /**
     * The ride takes over the surface the click came from, so it ends that action list. A cue step
     * of a timed sequence is the one exception: there the scene owns the timeline and the ride is
     * the cue at tick zero, so the later cues still fire.
     */
    private static boolean terminal() {
        ActionProgram.Frame frame = ActionProgram.current();
        return frame == null || !frame.cued();
    }
}