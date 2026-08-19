package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

@Director(name = "animations", aliases = {"animation"}, descriptionKey = "command.help.animations", description = "Animation tools")
public class CommandGlossAnimations {
    private static final String LIST_COMMAND = "/gloss animations list";

    private final Gloss plugin;

    public CommandGlossAnimations(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.animations.list", description = "List animation names")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page", description = "One-based list page") int page) {
        List<String> names = plugin.animations().names();
        if (names.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.ANIMATIONS_EMPTY);
            return;
        }

        GlossCommandPager.Window window = GlossCommandPager.window(names.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        GlossCommandMessages.send(sender, GlossMessages.ANIMATIONS_HEADER, MessageArgument.trusted("count", names.size()));
        for (String name : names.subList(window.startIndex(), window.endIndex())) {
            GlossCommandMessages.send(sender, GlossMessages.ANIMATIONS_ENTRY, MessageArgument.untrusted("name", name));
        }
        GlossCommandPager.sendFooter(sender, window, LIST_COMMAND);
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.animations.reset", description = "Restore shipped animation defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.animations.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "animation", name, plugin.animations().resetToDefault(name));
    }
}
