package art.arcane.gloss.behavior;

import art.arcane.gloss.api.GlossDialogSubmitEvent;
import art.arcane.gloss.api.GlossInventoryClickEvent;
import art.arcane.gloss.api.GlossMenuCloseEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code menu_close}, {@code dialog_submit} and {@code inventory_click} are subscribed reflectively,
 * by class and method name, so the behaviors lane compiles without the forms lane. Nothing else
 * would notice a rename on either side of that seam; this does.
 */
class BehaviorEventPublishersTest {
    @Test
    void everyOptionalTriggerFindsItsEventClassAndTheGettersItReadsThroughReflection() {
        assertReadable(GlossMenuCloseEvent.class, "getMenuId", null);
        assertReadable(GlossDialogSubmitEvent.class, "getDialogId", null);
        assertReadable(GlossInventoryClickEvent.class, "getInventoryId", "getSlot");
    }

    @Test
    void theEventsCarryWhatTheTriggerPutsInItsSelectorSlots() throws ReflectiveOperationException {
        GlossInventoryClickEvent click = new GlossInventoryClickEvent(viewer(), "shop", 13);

        assertEquals("shop", click.getClass().getMethod("getInventoryId").invoke(click));
        assertEquals(13, click.getClass().getMethod("getSlot").invoke(click));
        assertEquals("intro", new GlossMenuCloseEvent(viewer(), "intro").getMenuId());
        assertEquals(2, new GlossDialogSubmitEvent(viewer(), "confirm", 2).getButton());
    }

    private static void assertReadable(Class<?> type, String idGetter, String secondaryGetter) {
        assertTrue(Event.class.isAssignableFrom(type), type.getName() + " must be a Bukkit event");
        try {
            Method player = type.getMethod("getPlayer");
            assertEquals(Player.class, player.getReturnType());
            type.getMethod(idGetter);
            if (secondaryGetter != null) {
                type.getMethod(secondaryGetter);
            }
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(type.getName() + " lost a getter BehaviorTriggers reads", missing);
        }
    }

    private static Player viewer() {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "viewer";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
