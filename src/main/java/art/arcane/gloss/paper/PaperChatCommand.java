package art.arcane.gloss.paper;

import art.arcane.gloss.chat.ChatCommands;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;

final class PaperChatCommand implements BasicCommand {
    private final ChatCommands commands;
    private final String commandName;

    PaperChatCommand(ChatCommands commands, String commandName) {
        this.commands = commands;
        this.commandName = commandName;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        commands.execute(source.getSender(), commandName, args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return commands.complete(source.getSender(), commandName, args);
    }

    /** Brigadier asks this before listing or completing; execute enforces the same node. */
    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(ChatCommands.permissionFor(commandName));
    }
}
