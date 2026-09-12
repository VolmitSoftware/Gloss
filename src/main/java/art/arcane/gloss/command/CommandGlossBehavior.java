package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.behavior.BehaviorRuntime;
import art.arcane.gloss.behavior.BehaviorService;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /gloss behavior}: what is loaded, what each entry listens for, and a way to run one by hand. */
@Director(name = "behavior", aliases = {"behaviors"}, descriptionKey = "command.help.behavior.root",
    description = "Inspect and fire behavior documents")
public final class CommandGlossBehavior {
    private final Gloss plugin;

    public CommandGlossBehavior(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.behavior.list",
        description = "List loaded behavior documents")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page",
                         description = "One-based list page") int page) {
        if (GlossCommandMessages.denied(sender, "gloss.behaviors")) {
            return;
        }
        BehaviorService service = service(sender);
        if (service == null) {
            return;
        }
        List<BehaviorRuntime> runtimes = service.subscriptions().runtimes();
        if (runtimes.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_LIST_EMPTY);
            return;
        }
        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(
            runtimes.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        for (BehaviorRuntime runtime : runtimes.subList(window.startIndex(), window.endIndex())) {
            sendHeader(sender, runtime);
        }
        GlossCommandPager.sendPageFooter(sender, window, "/gloss behavior list");
    }

    @Director(name = "info", descriptionKey = "command.help.behavior.info",
        description = "Show a behavior document's triggers and gates")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.behavior.id",
                         description = "Behavior document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.behaviors")) {
            return;
        }
        BehaviorService service = service(sender);
        if (service == null) {
            return;
        }
        BehaviorRuntime runtime = service.subscriptions().runtime(id);
        if (runtime == null) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_MISSING, MessageArgument.untrusted("id", id));
            return;
        }
        sendHeader(sender, runtime);
        for (BehaviorRuntime.CompiledEntry entry : runtime.entries()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_INFO_ENTRY,
                MessageArgument.trusted("line", entry.index() + 1),
                MessageArgument.untrusted("name", entry.entry().trigger().key()),
                MessageArgument.untrusted("when", entry.entry().when() == null ? "true" : entry.entry().when()),
                MessageArgument.untrusted("permission",
                    entry.entry().permission() == null ? "-" : entry.entry().permission()));
        }
    }

    @Director(name = "fire", descriptionKey = "command.help.behavior.fire",
        description = "Run one behavior entry now")
    public void fire(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.behavior.id",
                         description = "Behavior document id") String id,
                     @Param(name = "entry", descriptionKey = "command.help.behavior.entry",
                         description = "Entry number, starting at 1") int entry,
                     @Param(name = "player", defaultValue = "*", descriptionKey = "command.help.behavior.player",
                         description = "Player to run the entry for, or * for none") String player) {
        if (GlossCommandMessages.denied(sender, "gloss.behaviors.fire")) {
            return;
        }
        BehaviorService service = service(sender);
        if (service == null) {
            return;
        }
        Player viewer = null;
        if (!"*".equals(player)) {
            viewer = Bukkit.getPlayerExact(player);
            if (viewer == null) {
                GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_PLAYER_MISSING,
                    MessageArgument.untrusted("player", player));
                return;
            }
        }
        if (!service.fireEntry(id, entry - 1, viewer)) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_FIRE_MISSING,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("line", entry));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_FIRED,
            MessageArgument.untrusted("id", id),
            MessageArgument.trusted("line", entry));
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.behavior.reset",
        description = "Restore shipped behavior defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name",
                          description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.behaviors.reset")) {
            return;
        }
        BehaviorService service = service(sender);
        if (service == null) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "behavior", name, service.resetToDefault(name));
    }

    private void sendHeader(CommandSender sender, BehaviorRuntime runtime) {
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_INFO_HEADER,
            MessageArgument.untrusted("id", runtime.id()),
            MessageArgument.trusted("revision", runtime.doc().revision()),
            MessageArgument.trusted("count", runtime.entries().size()),
            MessageArgument.trusted("enabled", runtime.doc().enabled()));
    }

    private BehaviorService service(CommandSender sender) {
        BehaviorService service = plugin == null ? null : plugin.service(BehaviorService.class);
        if (service == null || !service.enabled()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_OFF);
            return null;
        }
        return service;
    }
}
