package art.arcane.gloss.command;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There is exactly one help pipeline: {@link DirectorMiniMenu#deliver} on the GLOSS theme, the
 * same call {@link GlossCommandService} makes. A terminal cannot click or hover, so a non-player
 * sender must receive the plain console rendering rather than the MiniMessage page built for
 * players.
 */
class GlossCommandHelpDeliveryTest {

  @Test
  void aConsoleSenderReceivesThePlainConsoleHelpPage() {
    List<String> received = new ArrayList<>();
    deliver(console(received));

    assertFalse(received.isEmpty(), "console received nothing");
    assertTrue(received.get(0).startsWith("---"), received.get(0));
    for (String line : received) {
      assertFalse(line.contains("<gradient:"), line);
      assertFalse(line.contains("<click:"), line);
      assertFalse(line.contains("<hover:"), line);
      assertFalse(line.contains("<font:"), line);
    }
  }

  @Test
  void aPlayerSenderKeepsTheRichHelpPage() {
    List<String> received = new ArrayList<>();
    deliver(player(received));

    assertFalse(received.isEmpty(), "player received nothing");
    for (String line : received) {
      assertFalse(line.startsWith("---"), line);
    }
  }

  private static void deliver(CommandSender sender) {
    DirectorMiniMenu.deliver(sender, rootHelpPage(), GlossCommandService.menuTheme(), null);
  }

  private static DirectorMiniMenu.DirectorHelpPage rootHelpPage() {
    DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandGloss(null));
    Optional<DirectorMiniMenu.DirectorHelpPage> page = DirectorMiniMenu.resolveHelp(engine, List.of());
    return page.orElseThrow(() -> new IllegalStateException("no root help page"));
  }

  private static CommandSender console(List<String> received) {
    return (CommandSender) Proxy.newProxyInstance(GlossCommandHelpDeliveryTest.class.getClassLoader(),
        new Class<?>[]{CommandSender.class}, (proxy, method, args) -> capture(proxy, method.getName(), args, received));
  }

  private static Player player(List<String> received) {
    return (Player) Proxy.newProxyInstance(GlossCommandHelpDeliveryTest.class.getClassLoader(),
        new Class<?>[]{Player.class}, (proxy, method, args) -> capture(proxy, method.getName(), args, received));
  }

  private static Object capture(Object proxy, String name, Object[] args, List<String> received) {
    return switch (name) {
      case "sendMessage", "sendRichMessage", "sendPlainMessage" -> {
        String message = String.valueOf(args[0]);
        if (!message.trim().isEmpty()) {
          received.add(message);
        }
        yield null;
      }
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> "sender";
      default -> throw new UnsupportedOperationException(name);
    };
  }
}
