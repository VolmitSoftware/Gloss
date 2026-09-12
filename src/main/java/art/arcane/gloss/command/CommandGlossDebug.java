package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import org.bukkit.command.CommandSender;

@Director(name = "debug", description = "Diagnostic commands", descriptionKey = "command.help.debug")
public final class CommandGlossDebug {
    private final Gloss plugin;

    public CommandGlossDebug(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "version", description = "Show the installed plugin version", descriptionKey = "command.help.version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        ComponentMessenger.sendMarkup(sender, DirectorMiniMenu.version(
            "Gloss", plugin.getDescription().getVersion(), GlossCommandService.menuTheme()));
    }

    @Director(name = "dump", sync = true, description = "Create and optionally upload a diagnostic report", descriptionKey = "command.help.debugdump")
    public void dump(
        @Param(name = "upload", defaultValue = "true", description = "Upload the report to mclo.gs", descriptionKey = "command.help.debugdump_upload") boolean upload,
        @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.debugDump().request(sender, upload);
    }
}
