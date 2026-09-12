package art.arcane.gloss.paper;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCommands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

/**
 * Registers the player chat commands on a server that loaded Gloss through {@code paper-plugin.yml},
 * where {@code plugin.getCommand} is unavailable and the Brigadier lifecycle is the only route.
 */
public final class PaperChatCommandRegistrar {
    private PaperChatCommandRegistrar() {
    }

    public static void register(Gloss plugin, ChatCommands commands) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(ChatCommands.MESSAGE_COMMAND, "Send a private message.",
                List.of("tell", "w"), new PaperChatCommand(commands, ChatCommands.MESSAGE_COMMAND));
            event.registrar().register(ChatCommands.REPLY_COMMAND, "Reply to the last private message.",
                List.of(), new PaperChatCommand(commands, ChatCommands.REPLY_COMMAND));
            event.registrar().register(ChatCommands.CHANNEL_COMMAND, "Choose your chat channel.",
                List.of(), new PaperChatCommand(commands, ChatCommands.CHANNEL_COMMAND));
        });
    }
}
