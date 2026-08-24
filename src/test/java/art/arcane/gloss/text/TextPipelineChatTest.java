package art.arcane.gloss.text;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPipelineChatTest {
    @Test
    void authorizedChatTranslatesSharedColorsWithoutParsingMiniMessage() {
        Player player = player(Set.of("gloss.chat.color"));

        assertEquals(
            "<red>§x§f§f§0§0§a§aHello",
            TextPipeline.renderChat(player, "<red>[FF00AA]Hello", null, false, true));
    }

    @Test
    void unauthorizedChatKeepsColorSyntaxLiteral() {
        Player player = player(Set.of());

        assertEquals(
            "&cNo [FF00AA]color",
            TextPipeline.renderChat(player, "&cNo [FF00AA]color", null, false, true));
    }

    @Test
    void disabledChatColorKeepsAuthorizedSyntaxLiteral() {
        Player player = player(Set.of("gloss.chat.color"));

        assertEquals("&cNo color", TextPipeline.renderChat(player, "&cNo color", null, false, false));
    }

    private static Player player(Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "hasPermission" -> permissions.contains(String.valueOf(arguments[0]));
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Player[chat-test]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
