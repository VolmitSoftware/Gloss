package art.arcane.gloss.paper;

import art.arcane.gloss.command.GlossCommandService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;

final class GlossPaperCommand implements BasicCommand {
    private final GlossCommandService commandService;
    private final String commandName;

    GlossPaperCommand(GlossCommandService commandService, String commandName) {
        this.commandService = commandService;
        this.commandName = commandName;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        commandService.executeCommand(source.getSender(), commandName, commandName, args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return commandService.tabComplete(source.getSender(), commandName, args);
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return true;
    }
}
