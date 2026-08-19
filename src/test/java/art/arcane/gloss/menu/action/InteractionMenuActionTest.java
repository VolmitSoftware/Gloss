package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.ConnectActionData;
import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.TeleportActionData;
import art.arcane.gloss.util.common.TextUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InteractionMenuActionTest {
  @Test
  public void messageUsesMiniMessageAndTheClickingPlayerToken() {
    AtomicReference<Component> delivered = new AtomicReference<>();
    MessageMenuAction action = new MessageMenuAction(
        new MessageActionData("<green>Hello <bold>%player%</bold></green>", null));

    assertEquals(ActionOutcome.CONTINUE, action.execute(context(player(delivered))));
    assertEquals("Hello tester", TextUtils.content(delivered.get()));
  }

  @Test
  public void connectPayloadCanOnlyRequestTheFixedConnectSubchannel() throws Exception {
    byte[] payload = ConnectMenuAction.payload("lobby-1");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      assertEquals("Connect", input.readUTF());
      assertEquals("lobby-1", input.readUTF());
      assertEquals(-1, input.read());
    }
  }

  @Test
  public void messageFormattingCannotInstallClickOrInsertionActions() {
    AtomicReference<Component> delivered = new AtomicReference<>();
    MessageMenuAction action = new MessageMenuAction(new MessageActionData(
        "<click:open_url:'https://example.com'><insert:'unsafe'>Open</insert></click>", null));

    action.execute(context(player(delivered)));

    assertNoInteractions(delivered.get());
    assertEquals("Open", TextUtils.content(delivered.get()));
  }

  @Test
  public void invalidInteractionActionsAreDroppedWithoutDroppingValidNeighbors() {
    List<MenuActionData> data = new ArrayList<>();
    data.add(new MessageActionData(" ", null));
    data.add(new TeleportActionData("world", 0D, 64D, 0D, 0F, 0F, null));
    data.add(new ConnectActionData("bad server", null));
    data.add(new MessageActionData("<gold>Good</gold>", null));

    List<MenuAction<?>> actions = MenuAction.resolve(data, "shops/root", "destination");

    assertEquals(1, actions.size());
    assertTrue(actions.getFirst() instanceof MessageMenuAction);
  }

  private static ActionContext context(Player player) {
    return new ActionContext() {
      @Override
      public Player player() {
        return player;
      }

      @Override
      public String menuId() {
        return "shops/root";
      }

      @Override
      public String componentId() {
        return "destination";
      }

      @Override
      public HoloClickTrigger trigger() {
        return HoloClickTrigger.LEFT_CLICK;
      }

      @Override
      public NavigationResult navigate(NavigationRequest request) {
        return NavigationResult.DENIED;
      }
    };
  }

  private static Player player(AtomicReference<Component> delivered) {
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "sendMessage" -> {
            if (args != null && args.length == 1 && args[0] instanceof Component component) {
              delivered.set(component);
            }
            yield null;
          }
          case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-0000000000e1");
          case "getName" -> "tester";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[tester]";
          default -> throw new UnsupportedOperationException("the message action touched " + method.getName());
        });
  }

  private static void assertNoInteractions(Component component) {
    assertNull(component.clickEvent());
    assertNull(component.insertion());
    component.children().forEach(InteractionMenuActionTest::assertNoInteractions);
  }
}
