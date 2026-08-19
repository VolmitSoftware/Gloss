package art.arcane.gloss.api;

import org.bukkit.entity.Player;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HoloUiApiEventTest {

  @Test
  public void theTwoEventsHaveIndependentHandlerListsAndNoSharedBaseClass() {
    assertNotSame(GlossMenuOpenEvent.getHandlerList(), GlossMenuClickEvent.getHandlerList());
    assertSame(org.bukkit.event.Event.class, GlossMenuOpenEvent.class.getSuperclass());
    assertSame(org.bukkit.event.Event.class, GlossMenuClickEvent.class.getSuperclass());

    Player player = player();
    assertSame(GlossMenuOpenEvent.getHandlerList(),
        new GlossMenuOpenEvent(player, "shop", "ExampleShop").getHandlers());
    assertSame(GlossMenuClickEvent.getHandlerList(),
        new GlossMenuClickEvent(
            player, "shop", "buy", "ExampleShop", HoloClickTrigger.RIGHT_CLICK).getHandlers());
  }

  @Test
  public void bothEventsAreCancellableAndCarryTheOwnerPluginNameWhichIsNullForJsonMenus() {
    Player player = player();

    GlossMenuOpenEvent open = new GlossMenuOpenEvent(player, "shop", null);
    assertFalse(open.isCancelled());
    open.setCancelled(true);
    assertTrue(open.isCancelled());
    assertNull(open.getOwnerPluginName());
    assertEquals("shop", open.getMenuId());
    assertSame(player, open.getPlayer());

    GlossMenuClickEvent click = new GlossMenuClickEvent(
        player, "shop", "buy", "ExampleShop", HoloClickTrigger.SHIFT_LEFT_CLICK);
    assertFalse(click.isCancelled());
    click.setCancelled(true);
    assertTrue(click.isCancelled());
    assertEquals("buy", click.getComponentId());
    assertEquals("ExampleShop", click.getOwnerPluginName());
    assertEquals(HoloClickTrigger.SHIFT_LEFT_CLICK, click.getTrigger());

    assertThrows(NullPointerException.class, () -> new GlossMenuOpenEvent(null, "shop", null));
    assertThrows(NullPointerException.class, () -> new GlossMenuClickEvent(
        null, "shop", "buy", null, HoloClickTrigger.LEFT_CLICK));
    assertThrows(NullPointerException.class, () -> new GlossMenuClickEvent(
        player, "shop", "buy", null, null));
  }

  private static Player player() {
    UUID id = UUID.randomUUID();
    return (Player) Proxy.newProxyInstance(HoloUiApiEventTest.class.getClassLoader(),
        new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> id;
          case "getName" -> "tester";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "player";
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
