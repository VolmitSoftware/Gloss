package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

final class GlossCommandPager {
    static final int TEXT_PAGE_SIZE = DirectorMiniMenu.MENU_LINE_COUNT - 2;
    static final int ITEM_STATUS_PAGE_SIZE = DirectorMiniMenu.MENU_LINE_COUNT - 4;
    static final int EMOJI_COLUMNS = 3;
    static final int EMOJI_PAGE_SIZE = TEXT_PAGE_SIZE * EMOJI_COLUMNS;

    private GlossCommandPager() {
    }

    static DirectorMiniMenu.ContentPage window(int itemCount, int requestedPage, int pageSize) {
        return DirectorMiniMenu.paginate(itemCount, requestedPage, pageSize);
    }

    static void appendHeader(List<String> lines, String title, DirectorMiniMenu.ContentPage page,
                             DirectorMiniMenu.Theme theme) {
        lines.add(DirectorMiniMenu.banner(title, page, theme));
    }

    static void appendFooter(List<String> lines, DirectorMiniMenu.ContentPage page, String command,
                             DirectorMiniMenu.Theme theme) {
        lines.add(DirectorMiniMenu.paginationBar(page, command, theme, GlossLocalization.globalDirectorResolver()));
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
}
