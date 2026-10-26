package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class GlossCommandPager {
    static final int MENU_RESERVED_LINES = 2;
    static final int PAGE_FOOTER_LINES = 2;
    static final int EMOJI_COLUMNS = 3;

    private GlossCommandPager() {
    }

    static DirectorMiniMenu.ContentPage window(List<String> entries, int requestedPage, int reservedLines) {
        return DirectorMiniMenu.paginate(entries, requestedPage,
                DirectorMiniMenu.CHAT_LINE_COUNT - reservedLines, reservedLines);
    }

    static List<String> menu(String title, List<String> entries, int requestedPage, String command,
                             DirectorMiniMenu.Theme theme) {
        DirectorMiniMenu.ContentPage page = window(entries, requestedPage, MENU_RESERVED_LINES);
        List<String> lines = new ArrayList<>(page.endIndex() - page.startIndex() + MENU_RESERVED_LINES);
        appendHeader(lines, title, page, theme);
        lines.addAll(entries.subList(page.startIndex(), page.endIndex()));
        appendFooter(lines, page, command, theme);
        return lines;
    }

    static void appendHeader(List<String> lines, String title, DirectorMiniMenu.ContentPage page,
                             DirectorMiniMenu.Theme theme) {
        lines.add(DirectorMiniMenu.banner(title, page, theme));
    }

    static void appendFooter(List<String> lines, DirectorMiniMenu.ContentPage page, String command,
                             DirectorMiniMenu.Theme theme) {
        lines.add(DirectorMiniMenu.paginationBar(page, command, theme, GlossLocalization.globalDirectorResolver()));
    }

    static MessageRow row(TextKey key, MessageArgument... arguments) {
        return new MessageRow(key, GlossLocalization.args(arguments));
    }

    static DirectorMiniMenu.ContentPage sendRows(CommandSender sender, List<MessageRow> rows, int requestedPage,
                                                 int reservedLines) {
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        List<ComponentText> rendered = LanguageAudience.call(viewer, () -> render(rows));
        List<String> markup = new ArrayList<>(rendered.size());
        for (ComponentText line : rendered) {
            markup.add(line.miniMessage());
        }
        DirectorMiniMenu.ContentPage page = window(markup, requestedPage, reservedLines);
        for (ComponentText line : rendered.subList(page.startIndex(), page.endIndex())) {
            ComponentMessenger.send(sender, line);
        }
        return page;
    }

    /** The page counter and the next-page hint for a command that sends its rows as plain messages. */
    static void sendPageFooter(CommandSender sender, DirectorMiniMenu.ContentPage page, String command) {
        if (page.pages() <= 1) {
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.LIST_PAGE,
                MessageArgument.trusted("page", page.page()),
                MessageArgument.trusted("pages", page.pages()),
                MessageArgument.trusted("from", page.startIndex() + 1),
                MessageArgument.trusted("to", page.endIndex()),
                MessageArgument.trusted("total", page.total()));
        if (page.hasNext()) {
            GlossCommandMessages.send(sender, GlossMessages.LIST_NEXT,
                    MessageArgument.untrusted("command", page.nextCommand(command)));
        }
    }

    static String entry(String label, String details, DirectorMiniMenu.Theme theme) {
        StringBuilder line = new StringBuilder();
        line.append("<").append(theme.muted()).append(">⇀</").append(theme.muted()).append("> ")
                .append("<gradient:").append(theme.primaryLeft()).append(":").append(theme.primaryRight()).append(">")
                .append(DirectorMiniMenu.escapeText(label)).append("</gradient>");
        if (details != null && !details.isBlank()) {
            line.append(" <").append(theme.description()).append(">")
                    .append(DirectorMiniMenu.escapeText(details))
                    .append("</").append(theme.description()).append(">");
        }
        return line.toString();
    }

    private static List<ComponentText> render(List<MessageRow> rows) {
        List<ComponentText> rendered = new ArrayList<>(rows.size());
        for (MessageRow row : rows) {
            rendered.add(GlossLocalization.globalComponent(row.key(), row.arguments()));
        }
        return rendered;
    }

    record MessageRow(TextKey key, MessageArgs arguments) {
    }
}
