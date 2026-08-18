package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.emoji.EmojiEntry;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

@Director(name = "gloss", aliases = {"gl", "glo", "gg"}, descriptionKey = "command.help.root", description = "Gloss command root")
public class CommandGloss {
    private static final String SUPPORTED_MC_VERSION = "26.1.2 - 26.2";
    private static final int EMOJI_PAGE_SIZE = 8;

    private final Gloss plugin;
    private CommandGlossHologram hologram;
    private CommandGlossBoard board;
    private CommandGlossGroup group;

    public CommandGloss(Gloss plugin) {
        this.plugin = plugin;
        this.hologram = new CommandGlossHologram(plugin);
        this.board = new CommandGlossBoard(plugin);
        this.group = new CommandGlossGroup(plugin);
    }

    @Director(name = "emoji", descriptionKey = "command.help.emoji", description = "List enabled emoji; click one to insert it into chat")
    public void emoji(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.emoji.page", description = "Page number") int page) {
        if (GlossCommandMessages.denied(sender, "gloss.emoji.use")) {
            return;
        }

        List<EmojiEntry> enabled = enabledEmoji();
        if (enabled.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.EMOJI_EMPTY);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(enabled.size() / (double) EMOJI_PAGE_SIZE));
        int safePage = Math.max(1, Math.min(page, totalPages));
        int from = (safePage - 1) * EMOJI_PAGE_SIZE;
        int to = Math.min(enabled.size(), from + EMOJI_PAGE_SIZE);

        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        String hover = DirectorMiniMenu.escapeText(GlossLocalization.english()
                .directorText(GlossMessages.EMOJI_HOVER, MessageArgs.empty()));
        List<String> lines = new ArrayList<>(to - from + 2);
        lines.add(DirectorMiniMenu.banner("/gloss emoji " + safePage + "/" + totalPages, theme));
        for (int index = from; index < to; index++) {
            lines.add(renderEmojiEntry(enabled.get(index), theme, hover));
        }
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "animations", descriptionKey = "command.help.animations", description = "List animation names")
    public void animations(@Param(name = "sender", contextual = true) CommandSender sender) {
        List<String> names = plugin.animations().names();
        if (names.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.ANIMATIONS_EMPTY);
            return;
        }

        for (String name : names) {
            GlossCommandMessages.send(sender, GlossMessages.ANIMATIONS_ENTRY, MessageArgument.untrusted("name", name));
        }
    }

    @Director(name = "status", descriptionKey = "command.help.status", description = "Show terse runtime counts")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.admin")) {
            return;
        }

        GlossCommandMessages.send(sender, GlossMessages.STATUS_HOLOGRAMS,
                MessageArgument.trusted("count", plugin.holograms().hologramCount()),
                MessageArgument.trusted("temporary", plugin.holograms().temporaryCount()),
                MessageArgument.trusted("entities", plugin.holograms().activeEntityCount()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_BOARDS,
                MessageArgument.trusted("count", plugin.boards().boards().size()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_EMOJI,
                MessageArgument.trusted("enabled", enabledEmoji().size()),
                MessageArgument.trusted("total", plugin.emoji().all().size()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_ANIMATIONS,
                MessageArgument.trusted("count", plugin.animations().names().size()));
        GlossCommandMessages.send(sender, GlossMessages.STATUS_EFFECTS,
                MessageArgument.trusted("bubbles", plugin.bubbles().activeCount()),
                MessageArgument.trusted("indicators", plugin.indicators().activeCount()),
                MessageArgument.trusted("drops", plugin.drops().activeCount()));
    }

    @Director(name = "reload", sync = true, descriptionKey = "command.help.reload", description = "Reload Gloss configuration and services")
    public void reload(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.admin")) {
            return;
        }

        plugin.reloadAll();
        GlossCommandMessages.send(sender, GlossMessages.RELOAD_DONE);
    }

    @Director(name = "version", descriptionKey = "command.help.version", description = "Show the Gloss version and supported Minecraft range")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        GlossCommandMessages.send(sender, GlossMessages.VERSION_LINE,
                MessageArgument.trusted("version", plugin.getDescription().getVersion()),
                MessageArgument.trusted("mc", SUPPORTED_MC_VERSION));
    }

    private List<EmojiEntry> enabledEmoji() {
        List<EmojiEntry> all = plugin.emoji().all();
        List<EmojiEntry> enabled = new ArrayList<>(all.size());
        for (EmojiEntry entry : all) {
            if (entry.enabled()) {
                enabled.add(entry);
            }
        }
        return enabled;
    }

    private String renderEmojiEntry(EmojiEntry entry, DirectorMiniMenu.Theme theme, String hover) {
        String token = ":" + entry.id() + ":";
        String display = DirectorMiniMenu.escapeText(token);
        String glyph = DirectorMiniMenu.escapeText(entry.emoji());
        String suggest = token.replace("'", "");
        return "<hover:show_text:'" + hover + "'><click:suggest_command:'" + suggest + "'>"
                + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
                + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + display + "</gradient>"
                + " <white>" + glyph + "</white>"
                + "</click></hover>";
    }

}
