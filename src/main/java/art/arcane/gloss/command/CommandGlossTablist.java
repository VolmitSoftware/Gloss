package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.bukkit.command.CommandSender;

@Director(name = "tablist", descriptionKey = "command.help.tablist", description = "Tablist tools")
public class CommandGlossTablist {
    private final Gloss plugin;

    public CommandGlossTablist(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.tablist.reset", description = "Restore the shipped tablist document")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.tablist.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "tablist", "*", plugin.tablist().resetToDefault("*"));
    }
}
