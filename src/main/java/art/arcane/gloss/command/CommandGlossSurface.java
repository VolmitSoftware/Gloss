package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.surface.SurfaceRuntime;
import art.arcane.gloss.surface.SurfaceService;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Director(name = "surface", aliases = {"surfaces"}, descriptionKey = "command.help.surface.root",
    description = "Action bar, boss bar and title surfaces")
public class CommandGlossSurface {
    private static final String LIST_COMMAND = "/gloss surface list";

    private final Gloss plugin;

    public CommandGlossSurface(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.surface.list", description = "List surface documents")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page",
                         description = "One-based list page") int page) {
        List<SurfaceRuntime> runtimes = surfaces().runtimes();
        if (runtimes.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_SURFACES_EMPTY);
            return;
        }
        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(runtimes.size(), page,
            GlossCommandPager.TEXT_PAGE_SIZE);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        GlossCommandPager.appendHeader(lines, LIST_COMMAND + " · " + runtimes.size(), window, theme);
        for (SurfaceRuntime runtime : runtimes.subList(window.startIndex(), window.endIndex())) {
            lines.add(GlossCommandPager.entry(runtime.id(), runtime.kind().wireName(), theme));
        }
        GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", descriptionKey = "command.help.surface.info", description = "Show a surface document")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.surface.info.id",
                         description = "Surface document id") String id) {
        SurfaceRuntime runtime = surfaces().runtime(id);
        if (runtime == null) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_SURFACES_UNKNOWN,
                MessageArgument.untrusted("id", id));
            return;
        }
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(GlossCommandPager.entry(runtime.id(), runtime.kind().wireName(), theme));
        lines.add(GlossCommandPager.entry("show", runtime.doc().show().expression(), theme));
        lines.add(GlossCommandPager.entry("select", runtime.doc().select().priority()
            + " · " + runtime.doc().select().when(), theme));
        lines.add(GlossCommandPager.entry("variants", String.valueOf(runtime.doc().variants().size()), theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.surface.reset",
        description = "Restore shipped surface defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name",
                          description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.surfaces.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "surface", name, surfaces().resetToDefault(name));
    }

    @Director(name = "test", sync = true, descriptionKey = "command.help.surface.test",
        description = "Run the surface pass for one player now")
    public void test(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.surface.test.id",
                         description = "Surface document id") String id,
                     @Param(name = "player", defaultValue = "", descriptionKey = "command.help.surface.test.player",
                         description = "Online player to test against; defaults to you") String player) {
        if (GlossCommandMessages.denied(sender, "gloss.surfaces.test")) {
            return;
        }
        SurfaceRuntime runtime = surfaces().runtime(id);
        if (runtime == null) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_SURFACES_UNKNOWN,
                MessageArgument.untrusted("id", id));
            return;
        }
        Player target = target(sender, player);
        if (target == null) {
            GlossCommandMessages.send(sender, GlossMessages.SCREEN_PLAYER_UNKNOWN,
                MessageArgument.untrusted("player", player));
            return;
        }
        surfaces().applyNow(target);
        GlossCommandMessages.send(sender, GlossMessages.SCREEN_SURFACES_TESTED,
            MessageArgument.untrusted("id", runtime.id()),
            MessageArgument.untrusted("player", target.getName()));
    }

    private Player target(CommandSender sender, String player) {
        if (player == null || player.isBlank()) {
            return sender instanceof Player self ? self : null;
        }
        return Bukkit.getPlayerExact(player);
    }

    private SurfaceService surfaces() {
        return plugin.service(SurfaceService.class);
    }
}
