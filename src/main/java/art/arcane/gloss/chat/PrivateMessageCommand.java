package art.arcane.gloss.chat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class PrivateMessageCommand extends Command implements PluginIdentifiableCommand {
    private final Plugin plugin;
    private final ChatCommands executor;
    private final String command;

    PrivateMessageCommand(Plugin plugin, ChatCommands executor, String label) {
        super(label, description(label), usage(label), new ArrayList<>());
        this.plugin = plugin;
        this.executor = executor;
        this.command = PrivateMessageOwnership.commandFor(label);
    }

    @Override
    public @NotNull Plugin getPlugin() {
        return plugin;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        executor.execute(sender, command, args);
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias,
                                             @NotNull String[] args) {
        return executor.complete(sender, command, args);
    }

    private static String description(String label) {
        return ChatCommands.REPLY_COMMAND.equals(label) ? ChatCommands.REPLY_DESCRIPTION : ChatCommands.MESSAGE_DESCRIPTION;
    }

    private static String usage(String label) {
        return ChatCommands.REPLY_COMMAND.equals(label) ? "/<command> <message>" : "/<command> <player> <message>";
    }
}
