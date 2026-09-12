package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.nameplate.NameplateDoc;
import art.arcane.gloss.nameplate.NameplateRuntime;
import art.arcane.gloss.nameplate.NameplateService;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

@Director(name = "nameplate", aliases = {"nameplates"}, descriptionKey = "command.help.nameplate.root",
    description = "Player nameplate tools")
public final class CommandGlossNameplate {
    private final Gloss plugin;

    public CommandGlossNameplate(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.nameplate.list",
        description = "List nameplate documents")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.nameplates.list")) {
            return;
        }
        NameplateService nameplates = service(sender);
        if (nameplates == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (NameplateRuntime runtime : nameplates.documents()) {
            ids.add(runtime.id());
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_NAMEPLATE_LIST,
            MessageArgument.trusted("count", ids.size()),
            MessageArgument.trusted("value", String.join(", ", ids)));
    }

    @Director(name = "info", descriptionKey = "command.help.nameplate.info",
        description = "Show one nameplate document")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.nameplate.arg.id",
                         description = "Nameplate document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.nameplates.info")) {
            return;
        }
        NameplateService nameplates = service(sender);
        if (nameplates == null) {
            return;
        }
        for (NameplateRuntime runtime : nameplates.documents()) {
            if (!runtime.id().equals(id)) {
                continue;
            }
            GlossCommandMessages.send(sender, GlossMessages.WORLD_NAMEPLATE_INFO,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("value", runtime.priority()),
                MessageArgument.trusted("count", runtime.doc().presentation().lines().size()));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_NAMEPLATE_MISSING,
            MessageArgument.untrusted("id", id));
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.nameplate.reset",
        description = "Restore the shipped nameplate document")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name",
                          description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.nameplates.reset")) {
            return;
        }
        NameplateService nameplates = service(sender);
        if (nameplates == null) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, NameplateDoc.KIND, name,
            nameplates.resetToDefault(name));
    }

    @Director(name = "refresh", sync = true, descriptionKey = "command.help.nameplate.refresh",
        description = "Rebuild every viewer's nameplates")
    public void refresh(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.nameplates.refresh")) {
            return;
        }
        NameplateService nameplates = service(sender);
        if (nameplates == null) {
            return;
        }
        nameplates.refresh();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_NAMEPLATE_REFRESHED);
    }

    private NameplateService service(CommandSender sender) {
        NameplateService nameplates = plugin.service(NameplateService.class);
        if (nameplates == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
        }
        return nameplates;
    }
}
