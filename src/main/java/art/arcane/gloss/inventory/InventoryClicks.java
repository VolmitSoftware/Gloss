package art.arcane.gloss.inventory;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.ElementEvent;

import java.util.function.Consumer;

/**
 * The chest-click vocabulary. Four of VolmLib's seven element events are real player clicks a Gloss
 * action list can key on; middle click only exists in creative mode, so binding it would make an
 * action fire for some players and not others, and the two drag events never reach a slot because
 * the window cancels drags outright.
 */
public final class InventoryClicks {
    private InventoryClicks() {
    }

    /** @return the trigger this element event means, or null when it is not a click Gloss binds */
    public static HoloClickTrigger trigger(ElementEvent event) {
        return switch (event) {
            case LEFT -> HoloClickTrigger.LEFT_CLICK;
            case RIGHT -> HoloClickTrigger.RIGHT_CLICK;
            case SHIFT_LEFT -> HoloClickTrigger.SHIFT_LEFT_CLICK;
            case SHIFT_RIGHT -> HoloClickTrigger.SHIFT_RIGHT_CLICK;
            case MIDDLE, DRAG_INTO, OTHER_DRAG_INTO -> null;
        };
    }

    /** Wires the four bound clicks of one slot to a handler, off the same table. */
    public static void bind(Element element, Consumer<HoloClickTrigger> handler) {
        element.onLeftClick(clicked -> handler.accept(trigger(ElementEvent.LEFT)));
        element.onRightClick(clicked -> handler.accept(trigger(ElementEvent.RIGHT)));
        element.onShiftLeftClick(clicked -> handler.accept(trigger(ElementEvent.SHIFT_LEFT)));
        element.onShiftRightClick(clicked -> handler.accept(trigger(ElementEvent.SHIFT_RIGHT)));
    }
}
