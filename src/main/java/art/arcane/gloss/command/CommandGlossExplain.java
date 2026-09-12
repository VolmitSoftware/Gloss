package art.arcane.gloss.command;

import art.arcane.gloss.behavior.ExplainRegistry;
import art.arcane.gloss.behavior.ExplainReport;
import art.arcane.gloss.behavior.Explainable;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /gloss explain}: asks a registered runtime which conditions it consulted for one viewer
 * and prints them in the order they were consulted, with the deciding line marked.
 */
public final class CommandGlossExplain {
    public void explain(CommandSender sender, String kind, String id, String player, int page) {
        if (GlossCommandMessages.denied(sender, "gloss.debug.explain")) {
            return;
        }
        Explainable explainable = ExplainRegistry.global().find(kind);
        if (explainable == null) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPLAIN_UNKNOWN,
                MessageArgument.untrusted("kind", kind),
                MessageArgument.untrusted("options", String.join(", ", ExplainRegistry.global().kinds())));
            return;
        }
        Player viewer = viewer(sender, player);
        if (viewer == null && !"*".equals(player)) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_PLAYER_MISSING,
                MessageArgument.untrusted("player", player));
            return;
        }
        ExplainReport report = explainable.explain(id, viewer);
        if (report == null) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPLAIN_MISSING,
                MessageArgument.untrusted("kind", kind),
                MessageArgument.untrusted("id", id));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_EXPLAIN_HEADER,
            MessageArgument.untrusted("kind", report.kind()),
            MessageArgument.untrusted("id", report.id()),
            MessageArgument.untrusted("player", viewer == null ? "-" : viewer.getName()));
        List<ExplainReport.Line> lines = report.lines();
        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(
            lines.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        for (ExplainReport.Line line : lines.subList(window.startIndex(), window.endIndex())) {
            GlossCommandMessages.send(sender,
                line.winner() ? GlossMessages.BEHAVIORS_EXPLAIN_WINNER : GlossMessages.BEHAVIORS_EXPLAIN_LINE,
                MessageArgument.untrusted("path", line.path()),
                MessageArgument.untrusted("when", line.expression()),
                MessageArgument.untrusted("value", line.value()));
        }
        GlossCommandPager.sendPageFooter(sender, window, "/gloss explain " + kind + " " + id + " " + player);
    }

    private static Player viewer(CommandSender sender, String player) {
        if ("*".equals(player)) {
            return sender instanceof Player self ? self : null;
        }
        return Bukkit.getPlayerExact(player);
    }
}
