package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.leaderboard.LeaderboardDoc;
import art.arcane.gloss.leaderboard.LeaderboardService;
import art.arcane.gloss.leaderboard.LeaderboardView;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /gloss leaderboard} — inspects, clears and force-samples the computed leaderboards. */
@Director(name = "leaderboard", aliases = {"leaderboards"}, description = "Computed leaderboards",
    descriptionKey = "command.help.leaderboard.root")
public class CommandGlossLeaderboard {
    private static final String PERMISSION = "gloss.leaderboards";
    private static final String RESET_PERMISSION = "gloss.leaderboards.reset";
    private static final String SAMPLE_PERMISSION = "gloss.leaderboards.sample";
    private static final String ALL_PERIOD = "all";

    private final Gloss plugin;

    public CommandGlossLeaderboard(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", description = "List loaded leaderboards and their entry counts",
        descriptionKey = "command.help.leaderboard.list")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, PERMISSION)) {
            return;
        }
        LeaderboardService boards = plugin.service(LeaderboardService.class);
        List<String> ids = boards == null ? List.of() : boards.ids();
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(GlossLocalization.globalText(
            GlossMessages.LEADERBOARDS_LIST_HEADER,
            GlossLocalization.args(MessageArgument.trusted("count", ids.size()))), theme));
        if (ids.isEmpty()) {
            lines.add(GlossLocalization.globalText(GlossMessages.LEADERBOARDS_LIST_EMPTY));
        }
        for (String id : ids) {
            LeaderboardView view = boards.view(id);
            LeaderboardDoc doc = boards.document(id);
            lines.add("&7- &f" + id + " &7(" + doc.source().type().name().toLowerCase(Locale.ROOT)
                + ", " + (view == null ? 0 : view.size()) + " ranked, resets "
                + doc.reset().name().toLowerCase(Locale.ROOT) + ")");
        }
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", description = "Show a leaderboard's source, order and ranked entries",
        descriptionKey = "command.help.leaderboard.info")
    public void info(@Param(name = "id", description = "Leaderboard document id",
                         descriptionKey = "command.help.leaderboard.info.id",
                         customHandler = LeaderboardIdHandler.class) String id,
                     @Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, PERMISSION)) {
            return;
        }
        LeaderboardService boards = plugin.service(LeaderboardService.class);
        LeaderboardDoc doc = boards == null ? null : boards.document(id);
        if (doc == null) {
            GlossLocalization.sendGlobal(sender, GlossMessages.LEADERBOARD_UNKNOWN,
                GlossLocalization.args(MessageArgument.untrusted("id", id)));
            return;
        }
        LeaderboardView view = boards.view(id);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(id, theme));
        lines.add("&7source &f" + doc.source().type().name().toLowerCase(Locale.ROOT)
            + " &7order &f" + doc.order().name().toLowerCase(Locale.ROOT)
            + " &7reset &f" + doc.reset().name().toLowerCase(Locale.ROOT));
        if (view != null) {
            for (int rank = 1; rank <= view.size(); rank++) {
                LeaderboardView.Row row = view.row(rank);
                lines.add("&7" + rank + ". &f" + row.name() + " &7" + row.formatted());
            }
        }
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "reset", sync = true, description = "Clear a leaderboard's period or all-time values",
        descriptionKey = "command.help.leaderboard.reset")
    public void reset(@Param(name = "id", description = "Leaderboard document id",
                          descriptionKey = "command.help.leaderboard.reset.id",
                          customHandler = LeaderboardIdHandler.class) String id,
                      @Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "period", defaultValue = "current", description = "all or current",
                          descriptionKey = "command.help.leaderboard.reset.period") String period) {
        if (GlossCommandMessages.denied(sender, RESET_PERMISSION)) {
            return;
        }
        LeaderboardService boards = plugin.service(LeaderboardService.class);
        boolean allTime = ALL_PERIOD.equalsIgnoreCase(period);
        if (boards == null || !boards.resetBoard(id, allTime)) {
            GlossLocalization.sendGlobal(sender, GlossMessages.LEADERBOARD_UNKNOWN,
                GlossLocalization.args(MessageArgument.untrusted("id", id)));
            return;
        }
        GlossLocalization.sendGlobal(sender, GlossMessages.LEADERBOARD_RESET_DONE,
            GlossLocalization.args(MessageArgument.untrusted("id", id),
                MessageArgument.untrusted("state", allTime ? ALL_PERIOD : "current")));
    }

    @Director(name = "sample", sync = true,
        description = "Sample a leaderboard for every online player now",
        descriptionKey = "command.help.leaderboard.sample")
    public void sample(@Param(name = "id", description = "Leaderboard document id",
                           descriptionKey = "command.help.leaderboard.sample.id",
                           customHandler = LeaderboardIdHandler.class) String id,
                       @Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, SAMPLE_PERMISSION)) {
            return;
        }
        LeaderboardService boards = plugin.service(LeaderboardService.class);
        if (boards == null || !boards.sampleNow(id)) {
            GlossLocalization.sendGlobal(sender, GlossMessages.LEADERBOARD_UNKNOWN,
                GlossLocalization.args(MessageArgument.untrusted("id", id)));
            return;
        }
        GlossLocalization.sendGlobal(sender, GlossMessages.LEADERBOARD_SAMPLED,
            GlossLocalization.args(MessageArgument.untrusted("id", id)));
    }

    public static final class LeaderboardIdHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            KList<String> ids = new KList<>();
            Gloss plugin = Gloss.instance;
            LeaderboardService boards = plugin == null ? null : plugin.service(LeaderboardService.class);
            if (boards != null) {
                ids.addAll(boards.ids());
            }
            return ids;
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.isBlank()) {
                throw new DirectorParsingException("leaderboard id is required");
            }
            return in.strip();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
