package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;

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
