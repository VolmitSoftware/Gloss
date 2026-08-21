package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.bukkit.command.CommandSender;

@Director(name = "drops", aliases = {"drop"}, descriptionKey = "command.help.drops", description = "Dropped item presentation tools")
public class CommandGlossDrops {
    private final Gloss plugin;

    public CommandGlossDrops(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.drops.reset", description = "Restore the shipped real drop settings document")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.drops.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "real drop", name, plugin.drops().resetToDefault(name));
    }
}
