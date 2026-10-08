package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SurfaceMenuAction;

import java.util.List;

public record SurfaceActionData(String surface, Audience audience, HoloClickTrigger trigger, String when,
                                Integer cooldownTicks) implements MenuActionData {
    public SurfaceActionData {
        audience = audience == null ? Audience.VIEWER : audience;
    }

    @Override
    public MenuActionType getType() {
        return MenuActionType.SURFACE;
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new SurfaceMenuAction(this);
    }

    @Override
    public String invalidReason() {
        return surface == null || !surface.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
            ? "surface requires a document id" : null;
    }

    public record Audience(String scope, Double radius, ShowCondition when) {
        public static final Audience VIEWER = new Audience(null, null, null);

        public Audience {
            scope = scope == null ? "viewer" : scope;
            if (!List.of("viewer", "server", "world", "radius").contains(scope)) {
                throw new IllegalArgumentException("surface audience scope must be viewer, server, world or radius");
            }
            if (scope.equals("radius") && (radius == null || !Double.isFinite(radius) || radius <= 0 || radius > 4096)) {
                throw new IllegalArgumentException("surface audience radius must be positive and at most 4096 blocks");
            }
            if (!scope.equals("radius") && radius != null) {
                throw new IllegalArgumentException("surface audience radius is only valid for radius scope");
            }
            when = when == null ? ShowCondition.ALWAYS : when;
        }
    }
}
