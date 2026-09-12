package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.camera.Spline;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.CameraMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes the clicking player on a camera ride. Returns {@code STOP} from a click: the ride owns the
 * player until it ends, so nothing after it in that action list runs. As a cue of a timed
 * {@code sequence} it returns {@code CONTINUE}, because there the scene owns the timeline and the
 * ride is the cue at tick zero.
 */
public record CameraActionData(List<Node> path, Boolean skippable, Boolean letterbox,
                               HoloClickTrigger trigger, String when,
                               Integer cooldownTicks) implements MenuActionData {
    public record Node(Double x, Double y, Double z, Float yaw, Float pitch, Integer durationTicks) {
    }

    public CameraActionData {
        path = path == null ? List.of() : List.copyOf(path);
    }

    @Override
    public MenuActionType getType() {
        return MenuActionType.CAMERA;
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new CameraMenuAction(this);
    }

    public boolean skippableOrDefault() {
        return skippable == null || skippable;
    }

    public boolean letterboxOrDefault() {
        return letterbox != null && letterbox;
    }

    public List<Spline.Node> nodes() {
        List<Spline.Node> nodes = new ArrayList<>(path.size());
        for (Node node : path) {
            nodes.add(new Spline.Node(node.x(), node.y(), node.z(),
                node.yaw() == null ? 0.0F : node.yaw(), node.pitch() == null ? 0.0F : node.pitch(),
                node.durationTicks() == null ? 20 : node.durationTicks()));
        }
        return nodes;
    }

    @Override
    public String invalidReason() {
        if (path.isEmpty()) {
            return "declares a camera action with no path";
        }
        for (Node node : path) {
            if (node == null || node.x() == null || node.y() == null || node.z() == null) {
                return "declares a camera path node without coordinates";
            }
        }
        return null;
    }
}
