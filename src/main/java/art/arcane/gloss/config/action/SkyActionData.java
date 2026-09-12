package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.SkyMenuAction;
import art.arcane.gloss.sky.SkyOverride;

/**
 * Claims the clicking player's sky under {@code purpose}. Any field may be the literal
 * {@code "reset"}, which releases the purpose and hands the sky back to whoever held it before.
 */
public record SkyActionData(String purpose, String time, String weather, SkyOverride.Border border,
                            Integer fadeTicks, HoloClickTrigger trigger, String when,
                            Integer cooldownTicks) implements MenuActionData {
    public static final String RESET = "reset";

    @Override
    public MenuActionType getType() {
        return MenuActionType.SKY;
    }

    @Override
    public ActionEnvelope envelope() {
        return ActionEnvelope.of(when, cooldownTicks);
    }

    @Override
    public MenuAction<?> createAction() {
        return new SkyMenuAction(this);
    }

    public boolean releases() {
        return RESET.equalsIgnoreCase(time) || RESET.equalsIgnoreCase(weather);
    }

    public SkyOverride toOverride() {
        return new SkyOverride(purpose, parseTime(), RESET.equalsIgnoreCase(weather) ? null : weather,
            border, fadeTicks == null ? 0 : fadeTicks);
    }

    @Override
    public String invalidReason() {
        if (purpose == null || purpose.isBlank()) {
            return "declares a sky action without a purpose";
        }
        if (releases()) {
            return null;
        }
        if (time == null && weather == null && border == null) {
            return "declares a sky action that changes nothing";
        }
        if (time != null && parseTime() == null) {
            return "declares a sky time that is not a tick count";
        }
        return null;
    }

    private Long parseTime() {
        if (time == null || RESET.equalsIgnoreCase(time)) {
            return null;
        }
        try {
            return Long.parseLong(time.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
