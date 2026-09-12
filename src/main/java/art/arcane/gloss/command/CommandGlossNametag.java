package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.nametag.NametagRuntime;
import art.arcane.gloss.nametag.NametagService;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

@Director(name = "nametag", aliases = {"nametags"}, descriptionKey = "command.help.nametag.root",
    description = "Per-viewer nametag documents")
public class CommandGlossNametag {
    private static final String LIST_COMMAND = "/gloss nametag list";

    private final Gloss plugin;

    public CommandGlossNametag(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.nametag.list", description = "List nametag documents")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page",
                         description = "One-based list page") int page) {
        List<NametagRuntime> runtimes = nametags().runtimes();
        if (runtimes.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_NAMETAGS_EMPTY);
            return;
        }
        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(runtimes.size(), page,
            GlossCommandPager.TEXT_PAGE_SIZE);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        GlossCommandPager.appendHeader(lines, LIST_COMMAND + " · " + runtimes.size(), window, theme);
        for (NametagRuntime runtime : runtimes.subList(window.startIndex(), window.endIndex())) {
            lines.add(GlossCommandPager.entry(runtime.id(),
                runtime.viewerDependent() ? "per-viewer" : "broadcast", theme));
        }
        GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", descriptionKey = "command.help.nametag.info", description = "Show a nametag document")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.nametag.info.id",
                         description = "Nametag document id") String id) {
        NametagRuntime runtime = runtime(id);
        if (runtime == null) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_NAMETAGS_UNKNOWN,
                MessageArgument.untrusted("id", id));
            return;
        }
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(GlossCommandPager.entry(runtime.id(),
            runtime.viewerDependent() ? "per-viewer" : "broadcast", theme));
        lines.add(GlossCommandPager.entry("show", runtime.doc().show().expression(), theme));
        lines.add(GlossCommandPager.entry("select", runtime.doc().select().priority()
            + " · " + runtime.doc().select().when(), theme));
        lines.add(GlossCommandPager.entry("variants", String.valueOf(runtime.doc().variants().size()), theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.nametag.reset",
        description = "Restore shipped nametag defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name",
                          description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.nametags.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "nametag", name, nametags().resetToDefault(name));
    }

    @Director(name = "refresh", sync = true, descriptionKey = "command.help.nametag.refresh",
        description = "Re-apply every nametag now")
    public void refresh(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.nametags.refresh")) {
            return;
        }
        nametags().refresh();
        GlossCommandMessages.send(sender, GlossMessages.SCREEN_NAMETAGS_REFRESHED,
            MessageArgument.trusted("count", nametags().runtimes().size()));
    }

    private NametagRuntime runtime(String id) {
        for (NametagRuntime runtime : nametags().runtimes()) {
            if (runtime.id().equals(id)) {
                return runtime;
            }
        }
        return null;
    }

    private NametagService nametags() {
        return plugin.service(NametagService.class);
    }
}
