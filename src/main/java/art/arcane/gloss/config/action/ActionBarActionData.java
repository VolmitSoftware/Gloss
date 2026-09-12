package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.menu.action.ActionBarMenuAction;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.surface.SurfaceDoc;
import art.arcane.gloss.surface.SurfacePriorities;
import art.arcane.volmlib.util.hud.HudSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record ActionBarActionData(String text, Integer ticks, List<String> slots, String priority,
                                  HoloClickTrigger trigger, String when,
                                  Integer cooldownTicks) implements MenuActionData {
    public static final int DEFAULT_TICKS = 60;

    @Override
    public MenuActionType getType() {
        return MenuActionType.ACTIONBAR;
    }

    public int ticksOrDefault() {
        return ticks == null ? DEFAULT_TICKS : Math.clamp(ticks.intValue(), 1, SurfaceDoc.MAX_TTL_TICKS);
    }

    public List<HudSlot> hudSlots() {
        if (slots == null || slots.isEmpty()) {
            return List.of(HudSlot.CENTER);
        }
        List<HudSlot> resolved = new ArrayList<>(slots.size());
        for (String slot : slots) {
            HudSlot parsed = parse(slot);
            if (parsed != null && !resolved.contains(parsed)) {
                resolved.add(parsed);
            }
        }
        return resolved.isEmpty() ? List.of(HudSlot.CENTER) : List.copyOf(resolved);
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
        return new ActionBarMenuAction(this);
    }

    @Override
    public String invalidReason() {
        return text != null && !text.isBlank() ? null : "declares an empty action bar text";
    }

    private static HudSlot parse(String slot) {
        if (slot == null) {
            return null;
        }
        return switch (slot.trim().toLowerCase(Locale.ROOT)) {
            case "left" -> HudSlot.LEFT;
            case "center" -> HudSlot.CENTER;
            case "right" -> HudSlot.RIGHT;
            default -> null;
        };
    }
}
