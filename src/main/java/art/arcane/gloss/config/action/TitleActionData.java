package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.TitleMenuAction;
import art.arcane.gloss.surface.SurfaceDoc;
import art.arcane.gloss.surface.SurfacePriorities;

public record TitleActionData(String title, String subtitle, Integer fadeInTicks, Integer stayTicks,
                              Integer fadeOutTicks, String priority, HoloClickTrigger trigger,
                              String when, Integer cooldownTicks) implements MenuActionData {
    @Override
    public MenuActionType getType() {
        return MenuActionType.TITLE;
    }

    public String subtitleOrEmpty() {
        return subtitle == null ? "" : subtitle;
    }

    public int fadeInTicksOrDefault() {
        return clamp(fadeInTicks, SurfaceDoc.DEFAULT_FADE_IN_TICKS);
    }

    public int stayTicksOrDefault() {
        return clamp(stayTicks, SurfaceDoc.DEFAULT_STAY_TICKS);
    }

    public int fadeOutTicksOrDefault() {
        return clamp(fadeOutTicks, SurfaceDoc.DEFAULT_FADE_OUT_TICKS);
    }

    public int priorityValue() {
        return SurfacePriorities.of(priority == null ? "notice" : priority);
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new TitleMenuAction(this);
    }

    @Override
    public String invalidReason() {
        return title != null && !title.isBlank() ? null : "declares an empty title";
    }

    private static int clamp(Integer value, int fallback) {
        return value == null ? fallback : Math.clamp(value.intValue(), 0, SurfaceDoc.MAX_FADE_TICKS);
    }
}
