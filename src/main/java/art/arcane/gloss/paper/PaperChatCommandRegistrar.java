package art.arcane.gloss.paper;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.chat.ChatCommands;
import art.arcane.gloss.chat.PrivateMessageOwnership;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Registers the player chat commands on a server that loaded Gloss through {@code paper-plugin.yml},
 * where {@code plugin.getCommand} is unavailable and the Brigadier lifecycle is the only route.
 * Paper lets a main label replace an existing command but never an alias, so every private message
 * label Gloss owns is registered as its own main label.
 */
public final class PaperChatCommandRegistrar {
    private PaperChatCommandRegistrar() {
    }

    public static void register(Gloss plugin, ChatCommands commands, Supplier<PrivateMessageOwnership> ownership) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (Registration registration : registrations(ownership.get())) {
                event.registrar().register(registration.label(), registration.description(),
                    List.of(), new PaperChatCommand(commands, registration.command()));
            }
        });
    }

    static List<Registration> registrations(PrivateMessageOwnership ownership) {
        List<String> labels = ownership.labels();
        List<Registration> registrations = new ArrayList<>(labels.size() + 1);
        for (String label : labels) {
            String command = PrivateMessageOwnership.commandFor(label);
            String description = ChatCommands.REPLY_COMMAND.equals(command)
                ? ChatCommands.REPLY_DESCRIPTION
                : ChatCommands.MESSAGE_DESCRIPTION;
            registrations.add(new Registration(label, description, command));
        }
        registrations.add(new Registration(ChatCommands.CHANNEL_COMMAND, ChatCommands.CHANNEL_DESCRIPTION,
            ChatCommands.CHANNEL_COMMAND));
        return registrations;
    }

    record Registration(String label, String description, String command) {
    }
}
