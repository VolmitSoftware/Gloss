package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.GlowMenuAction;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.Locale;
import java.util.UUID;

/**
 * Makes an entity glow for the clicking player. {@code target} is {@code viewer}, {@code subject}
 * or an entity uuid; the tag lives under {@code purpose} so another owner's colour is not lost.
 */
public record GlowActionData(String target, String color, Integer ticks, String purpose,
                             Integer priority, HoloClickTrigger trigger, String when,
                             Integer cooldownTicks) implements MenuActionData {
    public static final String VIEWER = "viewer";
    public static final String SUBJECT = "subject";
    public static final String DEFAULT_PURPOSE = "action";

    @Override
    public MenuActionType getType() {
        return MenuActionType.GLOW;
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new GlowMenuAction(this);
    }

    public String purposeOrDefault() {
        return purpose == null || purpose.isBlank() ? DEFAULT_PURPOSE : purpose.trim();
    }

    public int priorityOrDefault() {
        return priority == null ? 0 : priority;
    }

    public long ticksOrDefault() {
        return ticks == null ? 0L : Math.max(0, ticks);
    }

    /** @return the uuid this action names, or null when it names a role instead */
    public UUID targetId() {
        if (target == null || isRole()) {
            return null;
        }
        try {
            return UUID.fromString(target.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    public boolean isRole() {
        String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        return normalized.equals(VIEWER) || normalized.equals(SUBJECT);
    }

    @Override
    public String invalidReason() {
        if (color == null || color.isBlank()) {
            return "declares a glow action without a color";
        }
        if (target != null && !isRole() && targetId() == null) {
            return "declares a glow target that is neither a role nor an entity uuid";
        }
        return null;
    }
}
