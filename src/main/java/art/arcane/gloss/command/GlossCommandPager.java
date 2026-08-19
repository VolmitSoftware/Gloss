package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

final class GlossCommandPager {
    static final int TEXT_PAGE_SIZE = DirectorMiniMenu.MENU_LINE_COUNT - 4;
    static final int ITEM_STATUS_PAGE_SIZE = DirectorMiniMenu.MENU_LINE_COUNT - 6;
    static final int EMOJI_COLUMNS = 3;
    static final int EMOJI_PAGE_SIZE = TEXT_PAGE_SIZE * EMOJI_COLUMNS;

    private GlossCommandPager() {
    }

    static int pageCount(int itemCount, int pageSize) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("item count must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("page size must be positive");
        }
        return itemCount == 0 ? 1 : ((itemCount - 1) / pageSize) + 1;
    }

    static Window window(int itemCount, int requestedPage, int pageSize) {
        int pages = pageCount(itemCount, pageSize);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, itemCount);
        return new Window(page, pages, startIndex, endIndex, itemCount);
    }

    static String nextCommand(String command, Window window) {
        return command + " page=" + (window.page() + 1);
    }

    static void sendFooter(CommandSender sender, Window window, String command) {
        GlossCommandMessages.send(sender, GlossMessages.LIST_PAGE,
                MessageArgument.trusted("page", window.page()),
                MessageArgument.trusted("pages", window.pages()),
                MessageArgument.trusted("from", window.startIndex() + 1),
                MessageArgument.trusted("to", window.endIndex()),
                MessageArgument.trusted("total", window.total()));
        if (!window.hasNext()) {
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.LIST_NEXT,
                MessageArgument.untrusted("command", nextCommand(command, window)));
    }

    static void appendFooter(List<String> lines, Window window, String command, DirectorMiniMenu.Theme theme) {
        lines.add("<" + theme.description() + ">"
                + DirectorMiniMenu.escapeText(pageText(window))
                + "</" + theme.description() + ">");
        if (!window.hasNext()) {
            return;
        }
        String next = nextCommand(command, window);
        lines.add("<click:run_command:" + next + ">"
                + "<" + theme.optional() + ">"
                + DirectorMiniMenu.escapeText(nextText(next))
                + "</" + theme.optional() + "></click>");
    }

    private static String pageText(Window window) {
        return GlossLocalization.globalDirectorText(GlossMessages.LIST_PAGE,
                MessageArgs.builder()
                        .trusted("page", window.page())
                        .trusted("pages", window.pages())
                        .trusted("from", window.startIndex() + 1)
                        .trusted("to", window.endIndex())
                        .trusted("total", window.total())
                        .build());
    }

    private static String nextText(String command) {
        return GlossLocalization.globalDirectorText(GlossMessages.LIST_NEXT,
                MessageArgs.builder().untrusted("command", command).build());
    }

    record Window(int page, int pages, int startIndex, int endIndex, int total) {
        boolean hasNext() {
            return page < pages;
        }
    }
}
