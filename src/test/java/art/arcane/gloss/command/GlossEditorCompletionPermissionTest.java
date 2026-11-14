package art.arcane.gloss.command;

import art.arcane.gloss.editor.EditorPermissions;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossEditorCompletionPermissionTest {
  private static final String BASE_PERMISSION = "gloss.emoji.use";

  @Test
  void hidesTheEditorTreeWithoutThePairPermission() {
    GlossCommandService commands = new GlossCommandService(null);
    CommandSender sender = sender(Set.of(BASE_PERMISSION));

    assertFalse(commands.tabComplete(sender, "gloss", new String[]{""}).contains("editor"));
    assertTrue(commands.tabComplete(sender, "gloss", new String[]{"editor", ""}).isEmpty());
  }

  @Test
  void offersTheEditorTreeWithThePairPermission() {
    GlossCommandService commands = new GlossCommandService(null);
    CommandSender sender = sender(Set.of(BASE_PERMISSION, EditorPermissions.PAIR));

    assertTrue(commands.tabComplete(sender, "gloss", new String[]{""}).contains("editor"));
    assertTrue(commands.tabComplete(sender, "gloss", new String[]{"editor", ""}).containsAll(
        Set.of("pair", "status", "tokens", "revoke")));
  }

  private static CommandSender sender(Set<String> permissions) {
    return (CommandSender) Proxy.newProxyInstance(
        GlossEditorCompletionPermissionTest.class.getClassLoader(),
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
