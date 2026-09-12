package art.arcane.gloss.inventory;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.enums.NavigationMode;
import art.arcane.volmlib.util.inventorygui.ElementEvent;
import art.arcane.volmlib.util.inventorygui.UIElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which chest clicks become which Gloss trigger. Middle click is creative-only in vanilla, so it is
 * left unbound rather than silently behaving like a left click; drags never reach a slot's actions
 * at all because the window cancels them.
 */
class InventoryClickRoutingTest {

    @Test
    void theFourBoundEventsMapToTheirTriggers() {
        assertEquals(HoloClickTrigger.LEFT_CLICK, InventoryClicks.trigger(ElementEvent.LEFT));
        assertEquals(HoloClickTrigger.RIGHT_CLICK, InventoryClicks.trigger(ElementEvent.RIGHT));
        assertEquals(HoloClickTrigger.SHIFT_LEFT_CLICK, InventoryClicks.trigger(ElementEvent.SHIFT_LEFT));
        assertEquals(HoloClickTrigger.SHIFT_RIGHT_CLICK, InventoryClicks.trigger(ElementEvent.SHIFT_RIGHT));
    }

    @Test
    void middleClickAndDragsAreNotTriggers() {
        assertNull(InventoryClicks.trigger(ElementEvent.MIDDLE));
        assertNull(InventoryClicks.trigger(ElementEvent.DRAG_INTO));
        assertNull(InventoryClicks.trigger(ElementEvent.OTHER_DRAG_INTO));
    }

    @Test
    void aBoundElementReportsEveryClickItsOwnWay() {
        List<HoloClickTrigger> seen = new ArrayList<>();
        UIElement element = new UIElement("slot-10");
        InventoryClicks.bind(element, seen::add);

        element.call(ElementEvent.LEFT, element);
        element.call(ElementEvent.RIGHT, element);
        element.call(ElementEvent.SHIFT_LEFT, element);
        element.call(ElementEvent.SHIFT_RIGHT, element);

        assertEquals(List.of(HoloClickTrigger.LEFT_CLICK, HoloClickTrigger.RIGHT_CLICK,
            HoloClickTrigger.SHIFT_LEFT_CLICK, HoloClickTrigger.SHIFT_RIGHT_CLICK), seen);
    }

    @Test
    void aBoundElementIgnoresMiddleClicksAndDrags() {
        List<HoloClickTrigger> seen = new ArrayList<>();
        UIElement element = new UIElement("slot-10");
        InventoryClicks.bind(element, seen::add);

        element.call(ElementEvent.MIDDLE, element);
        element.call(ElementEvent.DRAG_INTO, element);
        element.call(ElementEvent.OTHER_DRAG_INTO, element);

        assertEquals(List.of(), seen);
    }

    @Test
    void aSlotContextNamesItsWindowAndSlot() {
        InventoryActionContext context = new InventoryActionContext(null, "shop", 10,
            HoloClickTrigger.RIGHT_CLICK, Map.of("tier", "gold"), request -> NavigationResult.APPLIED);
        assertEquals("inventory:shop", context.menuId());
        assertEquals("slot:10", context.componentId());
        assertEquals(HoloClickTrigger.RIGHT_CLICK, context.trigger());
        assertEquals(NavigationResult.APPLIED,
            context.navigate(new NavigationRequest(NavigationMode.BACK, null)));
        assertEquals("gold", context.conditionScope().variable("args.tier"));
    }
}
