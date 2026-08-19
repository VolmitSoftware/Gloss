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

@Director(name = "emoji", descriptionKey = "command.help.emoji", description = "Emoji chat tools")
public class CommandGlossEmoji {
    private static final String LIST_COMMAND = "/gloss emoji list";

    private final Gloss plugin;

    public CommandGlossEmoji(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.emoji.list", description = "List enabled emoji; click one to insert it into chat")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page", description = "One-based list page") int page) {
        if (GlossCommandMessages.denied(sender, "gloss.emoji.use")) {
            return;
        }

        List<EmojiEntry> enabled = enabledEmoji(plugin);
        if (enabled.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.EMOJI_EMPTY);
            return;
        }

        GlossCommandPager.Window window = GlossCommandPager.window(enabled.size(), page, GlossCommandPager.EMOJI_PAGE_SIZE);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        String hover = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(GlossMessages.EMOJI_HOVER, MessageArgs.empty()));
        List<String> lines = new ArrayList<>();
        lines.add(DirectorMiniMenu.banner(LIST_COMMAND, theme));
        appendEmojiRows(lines, enabled, window, theme, hover);
        GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
        lines.add(DirectorMiniMenu.bar(theme));
        DirectorMiniMenu.deliver(sender, lines);
    }

    private void appendEmojiRows(List<String> lines, List<EmojiEntry> enabled, GlossCommandPager.Window window,
                                 DirectorMiniMenu.Theme theme, String hover) {
        StringBuilder row = new StringBuilder();
        int column = 0;
        for (int index = window.startIndex(); index < window.endIndex(); index++) {
            if (column > 0) {
                row.append(' ');
            }
            row.append(renderEmojiEntry(enabled.get(index), theme, hover));
            column++;
            if (column < GlossCommandPager.EMOJI_COLUMNS) {
                continue;
            }
            lines.add(row.toString());
            row.setLength(0);
            column = 0;
        }
        if (column > 0) {
            lines.add(row.toString());
        }
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.emoji.reset", description = "Restore shipped emoji defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.emoji.reset")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "emoji", name, plugin.emoji().resetToDefault(name));
    }

    static List<EmojiEntry> enabledEmoji(Gloss plugin) {
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
