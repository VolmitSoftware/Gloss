package art.arcane.gloss.paper;

import art.arcane.gloss.chat.ChatCommands;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On the Brigadier registration path the command list and its completions are gated by canUse, so
 * a player who cannot use /msg should not be offered it.
 */
class PaperChatCommandTest {

    @Test
    void eachCommandIsOfferedOnlyToSendersWhoHoldItsNode() {
        CommandSender whisperer = sender(Set.of(ChatCommands.MESSAGE_PERMISSION));
        CommandSender nobody = sender(Set.of());

        assertTrue(new PaperChatCommand(null, ChatCommands.MESSAGE_COMMAND).canUse(whisperer));
        assertTrue(new PaperChatCommand(null, ChatCommands.REPLY_COMMAND).canUse(whisperer));
        assertFalse(new PaperChatCommand(null, ChatCommands.CHANNEL_COMMAND).canUse(whisperer));
        assertFalse(new PaperChatCommand(null, ChatCommands.MESSAGE_COMMAND).canUse(nobody));
    }

    private static CommandSender sender(Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
            new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> permissions.contains(String.valueOf(args[0]));
                case "getName" -> "Tester";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "CommandSender[Tester]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
