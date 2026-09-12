package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.forge.GlyphService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Director(name = "forge", aliases = {"glyphs"}, descriptionKey = "command.help.forge.root",
    description = "Glyph pack tools")
public class CommandGlossForge {
    private final Gloss plugin;

    public CommandGlossForge(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "build", sync = true, descriptionKey = "command.help.forge.build",
        description = "Rebuild the glyph resource pack now")
    public void build(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.forge.build")) {
            return;
        }
        GlyphService service = service();
        if (service == null) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_DISABLED);
            return;
        }
        if (!service.build()) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_BUILD_FAILED);
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.FORGE_BUILT,
            MessageArgument.trusted("count", service.glyphs().all().size()),
            MessageArgument.untrusted("value", service.artifact()
                .map(artifact -> artifact.sha1Hex().substring(0, 8)).orElse("")));
    }

    @Director(name = "status", descriptionKey = "command.help.forge.status",
        description = "Show pack, glyph and delivery counts")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.forge")) {
            return;
        }
        GlyphService service = service();
        if (service == null) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_DISABLED);
            return;
        }
        for (String line : service.status()) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_STATUS_LINE,
                MessageArgument.untrusted("value", line));
        }
    }

    @Director(name = "export", sync = true, descriptionKey = "command.help.forge.export",
        description = "Copy the mergeable pack folder somewhere")
    public void export(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "path", descriptionKey = "command.help.forge.export.path",
                           description = "Destination folder for the pack") String path) {
        if (GlossCommandMessages.denied(sender, "gloss.forge.export")) {
            return;
        }
        GlyphService service = service();
        if (service == null) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_DISABLED);
            return;
        }
        try {
            int files = service.export(Path.of(path));
            GlossCommandMessages.send(sender, GlossMessages.FORGE_EXPORTED,
                MessageArgument.trusted("count", files), MessageArgument.untrusted("path", path));
        } catch (IOException | RuntimeException failure) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_EXPORT_FAILED,
                MessageArgument.untrusted("reason", String.valueOf(failure.getMessage())));
        }
    }

    @Director(name = "serve", sync = true, descriptionKey = "command.help.forge.serve",
        description = "Start or stop the embedded pack listener")
    public void serve(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "state", descriptionKey = "command.help.forge.serve.state",
                          description = "on or off") String state) {
        if (GlossCommandMessages.denied(sender, "gloss.forge.serve")) {
            return;
        }
        GlyphService service = service();
        if (service == null) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_DISABLED);
            return;
        }
        boolean on = "on".equals(state.toLowerCase(Locale.ROOT)) || "true".equals(state.toLowerCase(Locale.ROOT));
        boolean changed = service.delivery().serve(on);
        GlossCommandMessages.send(sender,
            changed ? GlossMessages.FORGE_SERVE : GlossMessages.FORGE_SERVE_UNCHANGED,
            MessageArgument.untrusted("state", on ? "on" : "off"));
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.forge.reset",
        description = "Restore shipped glyph documents")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name",
                          description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.forge.reset")) {
            return;
        }
        GlyphService service = service();
        if (service == null) {
            GlossCommandMessages.send(sender, GlossMessages.FORGE_DISABLED);
            return;
        }
        List<String> written = service.resetToDefault(name);
        GlossCommandMessages.sendResetResult(sender, "glyph", name, written);
    }

    private GlyphService service() {
        Gloss current = plugin == null ? Gloss.instance : plugin;
        return current == null ? null : current.service(GlyphService.class);
    }
}
