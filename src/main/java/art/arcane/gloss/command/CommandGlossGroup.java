package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.group.GlossGroup;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

@Director(name = "group", descriptionKey = "command.help.group", description = "Inspect Gloss display groups")
public class CommandGlossGroup {
    private final Gloss plugin;

    public CommandGlossGroup(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.group.list", description = "List display groups")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        List<String> names = plugin.groups().groupNames();
        if (names.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.GROUP_EMPTY);
            return;
        }

        for (String name : names) {
            GlossCommandMessages.send(sender, GlossMessages.GROUP_LIST_ENTRY, MessageArgument.untrusted("name", name));
        }
    }

    @Director(name = "info", descriptionKey = "command.help.group.info", description = "Show a group's tablist name and default board")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "name", descriptionKey = "command.help.group.info.name", description = "Group name") String name) {
        GlossGroup group = plugin.groups().group(name);
        if (group == null) {
            GlossCommandMessages.send(sender, GlossMessages.GROUP_MISSING, MessageArgument.untrusted("name", name));
            return;
        }

        GlossCommandMessages.send(sender, GlossMessages.GROUP_INFO,
                MessageArgument.untrusted("name", group.name()),
                MessageArgument.trusted("tablist", orDash(group.tablistName())),
                MessageArgument.trusted("board", orDash(group.defaultBoard())));
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
