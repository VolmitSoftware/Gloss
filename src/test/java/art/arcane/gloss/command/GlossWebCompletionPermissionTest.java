package art.arcane.gloss.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossWebCompletionPermissionTest {
  private static final String BASE_PERMISSION = "gloss.emoji.use";

  @Test
  void hidesTheWebTreeWithoutAnyWebPermission() {
    GlossCommandService commands = new GlossCommandService(null);
    CommandSender sender = sender(Set.of(BASE_PERMISSION));

    assertFalse(commands.tabComplete(sender, "gloss", new String[]{""}).contains("web"));
    assertTrue(commands.tabComplete(sender, "gloss", new String[]{"web", ""}).isEmpty());
    assertTrue(commands.tabComplete(
        sender, "gloss", new String[]{"web", "edit", "menu", ""}).isEmpty());
    assertTrue(commands.tabComplete(
        sender, "gloss", new String[]{"web", "sessions", "status", ""}).isEmpty());
  }

  @Test
  void exposesOnlyTheGrantedWebBranch() {
    GlossCommandService commands = new GlossCommandService(null);
    CommandSender sender = sender(Set.of(BASE_PERMISSION, CommandGlossWeb.EDIT_PERMISSION));

    assertTrue(commands.tabComplete(sender, "gloss", new String[]{""}).contains("web"));
    assertEquals(Set.of("edit"), Set.copyOf(
        commands.tabComplete(sender, "gloss", new String[]{"web", ""})));
    assertFalse(commands.tabComplete(
        sender, "gloss", new String[]{"web", "edit", ""}).isEmpty());
    assertTrue(commands.tabComplete(
        sender, "gloss", new String[]{"web", "sessions", ""}).isEmpty());
  }

  @Test
  void eachWebPermissionMapsToItsOwnBranch() {
    GlossCommandService commands = new GlossCommandService(null);
    assertBranch(commands, CommandGlossWeb.OPEN_PERMISSION, "open");
    assertBranch(commands, CommandGlossWeb.EDIT_PERMISSION, "edit");
    assertBranch(commands, CommandGlossWeb.WORKSPACE_PERMISSION, "workspace");
    assertBranch(commands, CommandGlossWebSessions.PERMISSION, "sessions");
  }

  private static void assertBranch(GlossCommandService commands, String permission, String branch) {
    CommandSender sender = sender(Set.of(BASE_PERMISSION, permission));
    List<String> suggestions = commands.tabComplete(sender, "gloss", new String[]{"web", ""});
    assertEquals(Set.of(branch), Set.copyOf(suggestions));
  }

  private static CommandSender sender(Set<String> permissions) {
    return (CommandSender) Proxy.newProxyInstance(
        GlossWebCompletionPermissionTest.class.getClassLoader(),
        new Class<?>[]{CommandSender.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "hasPermission" -> permissions.contains(String.valueOf(arguments[0]));
          case "getName" -> "test";
          case "isOp", "isPermissionSet" -> false;
          case "sendMessage", "sendRichMessage", "sendPlainMessage", "recalculatePermissions" -> null;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == arguments[0];
          case "toString" -> "test";
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
