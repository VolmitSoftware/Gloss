package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import org.bukkit.command.CommandSender;

@Director(name = "motd", descriptionKey = "command.help.motd", description = "MOTD tools")
public class CommandGlossMotd {
    private final Gloss plugin;

    public CommandGlossMotd(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.motd.reset", description = "Restore the shipped MOTD document")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.motd.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "MOTD", "*", plugin.motd().resetToDefault("*"));
    }
}
