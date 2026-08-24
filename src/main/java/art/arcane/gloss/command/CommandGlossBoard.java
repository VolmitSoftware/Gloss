package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.board.GlossBoardMeta;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Director(name = "board", aliases = {"boards", "sb", "bd"}, descriptionKey = "command.help.board", description = "Create and manage scoreboards")
public class CommandGlossBoard {
    private static final String CLEAR_PERMISSION_TOKEN = "default";
    private static final String DEFAULT_LINE = "&7A fresh Gloss board";
    private static final String LIST_COMMAND = "/gloss board list";

    private final Gloss plugin;

    public CommandGlossBoard(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "create", sync = true, descriptionKey = "command.help.board.create", description = "Create a scoreboard")
    public void create(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.board.create.id", description = "Unique board id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.create")) {
            return;
        }
        if (!plugin.boards().createBoard(id)) {
            GlossCommandMessages.send(sender, GlossMessages.BOARD_EXISTS, MessageArgument.untrusted("id", id));
            return;
        }

        GlossBoardMeta meta = plugin.boards().board(id);
        if (meta != null) {
            meta.setTitle("&d" + id);
            meta.addLine(DEFAULT_LINE);
            plugin.boards().saveBoard(meta);
        }
        GlossCommandMessages.send(sender, GlossMessages.BOARD_CREATED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "delete", sync = true, descriptionKey = "command.help.board.delete", description = "Delete a scoreboard")
    public void delete(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.board.delete.id", description = "Board id to delete") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.delete")) {
            return;
        }
        if (!plugin.boards().deleteBoard(id)) {
            GlossCommandMessages.send(sender, GlossMessages.BOARD_MISSING, MessageArgument.untrusted("id", id));
            return;
        }

        GlossCommandMessages.send(sender, GlossMessages.BOARD_DELETED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "title", sync = true, descriptionKey = "command.help.board.title", description = "Set a scoreboard title")
    public void title(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "id", descriptionKey = "command.help.board.title.id", description = "Board id") String id,
                      @Param(name = "text", descriptionKey = "command.help.board.title.text", description = "Title text; quote it to include spaces") String text) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }

        meta.setTitle(text);
        plugin.boards().saveBoard(meta);
        GlossCommandMessages.send(sender, GlossMessages.BOARD_TITLE_SET, MessageArgument.untrusted("id", id));
    }

    @Director(name = "addline", sync = true, descriptionKey = "command.help.board.addline", description = "Append a line to a scoreboard")
    public void addline(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "id", descriptionKey = "command.help.board.addline.id", description = "Board id") String id,
                        @Param(name = "text", descriptionKey = "command.help.board.addline.text", description = "Line text; quote it to include spaces") String text) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }

        meta.addLine(text);
        plugin.boards().saveBoard(meta);
        GlossCommandMessages.send(sender, GlossMessages.BOARD_LINE_ADDED,
                MessageArgument.trusted("line", meta.lines().size()),
                MessageArgument.untrusted("id", id));
    }

    @Director(name = "setline", sync = true, descriptionKey = "command.help.board.setline", description = "Replace a scoreboard line")
    public void setline(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "id", descriptionKey = "command.help.board.setline.id", description = "Board id") String id,
                        @Param(name = "line", descriptionKey = "command.help.board.setline.line", description = "Line number, starting at 1") int line,
                        @Param(name = "text", descriptionKey = "command.help.board.setline.text", description = "Line text; quote it to include spaces") String text) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }
        if (outOfRange(sender, meta, id, line)) {
            return;
        }

        meta.setLine(line - 1, text);
        plugin.boards().saveBoard(meta);
        GlossCommandMessages.send(sender, GlossMessages.BOARD_LINE_SET,
                MessageArgument.trusted("line", line),
                MessageArgument.untrusted("id", id));
    }

    @Director(name = "removeline", sync = true, descriptionKey = "command.help.board.removeline", description = "Remove a scoreboard line")
    public void removeline(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "id", descriptionKey = "command.help.board.removeline.id", description = "Board id") String id,
                           @Param(name = "line", descriptionKey = "command.help.board.removeline.line", description = "Line number, starting at 1") int line) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }
        if (outOfRange(sender, meta, id, line)) {
            return;
        }

        meta.removeLine(line - 1);
        plugin.boards().saveBoard(meta);
        GlossCommandMessages.send(sender, GlossMessages.BOARD_LINE_REMOVED,
                MessageArgument.trusted("line", line),
                MessageArgument.untrusted("id", id));
    }

    @Director(name = "show", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.board.show", description = "Show a scoreboard to yourself")
    public void show(@Param(name = "player", contextual = true) Player player,
                     @Param(name = "id", descriptionKey = "command.help.board.show.id", description = "Board id") String id) {
        if (GlossCommandMessages.denied(player, "gloss.boards.show")) {
            return;
        }
        if (find(player, id) == null) {
            return;
        }

        plugin.boards().setBoard(player, id);
        GlossCommandMessages.send(player, GlossMessages.BOARD_SHOWN, MessageArgument.untrusted("id", id));
    }

    @Director(name = "hide", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.board.hide", description = "Hide your scoreboard")
    public void hide(@Param(name = "player", contextual = true) Player player) {
        if (GlossCommandMessages.denied(player, "gloss.boards.hide")) {
            return;
        }

        plugin.boards().clearBoard(player);
        GlossCommandMessages.send(player, GlossMessages.BOARD_HIDDEN);
    }

    @Director(name = "primary", sync = true, descriptionKey = "command.help.board.primary", description = "Mark a scoreboard as the primary default board")
    public void primary(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "id", descriptionKey = "command.help.board.primary.id", description = "Board id") String id,
                        @Param(name = "enabled", defaultValue = "true", descriptionKey = "command.help.board.primary.enabled", description = "true to mark primary, false to unmark") boolean enabled) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }

        meta.setPrimary(enabled);
        plugin.boards().saveBoard(meta);
        GlossCommandMessages.send(sender, GlossMessages.BOARD_PRIMARY_SET,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("enabled", enabled));
    }

    @Director(name = "permission", sync = true, descriptionKey = "command.help.board.permission", description = "Set the permission required to see a scoreboard")
    public void permission(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "id", descriptionKey = "command.help.board.permission.id", description = "Board id") String id,
                           @Param(name = "node", descriptionKey = "command.help.board.permission.node", description = "Permission node; use default to clear") String node) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }

        if (CLEAR_PERMISSION_TOKEN.equalsIgnoreCase(node)) {
            meta.setPermission("");
            plugin.boards().saveBoard(meta);
            GlossCommandMessages.send(sender, GlossMessages.BOARD_PERMISSION_CLEARED, MessageArgument.untrusted("id", id));
            return;
        }

        meta.setPermission(node);
        plugin.boards().saveBoard(meta);
        GlossCommandMessages.send(sender, GlossMessages.BOARD_PERMISSION_SET,
                MessageArgument.untrusted("id", id),
                MessageArgument.untrusted("node", node));
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.board.reset", description = "Restore shipped scoreboard defaults")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.boards.edit")) {
            return;
        }
        GlossCommandMessages.sendResetResult(sender, "board", name, plugin.boards().resetToDefault(name));
    }

    @Director(name = "list", descriptionKey = "command.help.board.list", description = "List scoreboards")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page", description = "One-based list page") int page) {
        List<GlossBoardMeta> boards = plugin.boards().boards();
        if (boards.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.BOARD_LIST_EMPTY);
            return;
        }

        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(boards.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        String hover = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(GlossMessages.BOARD_LIST_HOVER, MessageArgs.empty()));
        List<String> lines = new ArrayList<>();
        GlossCommandPager.appendHeader(lines, LIST_COMMAND, window, theme);
        for (GlossBoardMeta meta : boards.subList(window.startIndex(), window.endIndex())) {
            lines.add(renderListEntry(meta, theme, hover));
        }
        GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", descriptionKey = "command.help.board.info", description = "Show a scoreboard's title, lines and settings")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.board.info.id", description = "Board id") String id) {
        GlossBoardMeta meta = find(sender, id);
        if (meta == null) {
            return;
        }

        String permission = meta.permission() == null || meta.permission().isBlank()
                ? GlossLocalization.globalText(GlossMessages.BOARD_PERMISSION_NONE)
                : meta.permission();
        GlossCommandMessages.send(sender, GlossMessages.BOARD_INFO_HEADER,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("title", meta.title()));
        GlossCommandMessages.send(sender, GlossMessages.BOARD_INFO_META,
                MessageArgument.trusted("primary", meta.primary()),
                MessageArgument.untrusted("permission", permission));
        List<String> lines = meta.lines();
        for (int index = 0; index < lines.size(); index++) {
            GlossCommandMessages.send(sender, GlossMessages.BOARD_INFO_LINE,
                    MessageArgument.trusted("line", index + 1),
                    MessageArgument.trusted("text", lines.get(index)));
        }
    }

    private GlossBoardMeta find(CommandSender sender, String id) {
        GlossBoardMeta meta = plugin.boards().board(id);
        if (meta == null) {
            GlossCommandMessages.send(sender, GlossMessages.BOARD_MISSING, MessageArgument.untrusted("id", id));
        }
        return meta;
    }

    private boolean outOfRange(CommandSender sender, GlossBoardMeta meta, String id, int line) {
        int count = meta.lines().size();
        if (line >= 1 && line <= count) {
            return false;
        }
        GlossCommandMessages.send(sender, GlossMessages.BOARD_LINE_OUT_OF_RANGE,
                MessageArgument.trusted("line", line),
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("count", count));
        return true;
    }

    private String renderListEntry(GlossBoardMeta meta, DirectorMiniMenu.Theme theme, String hover) {
        String display = DirectorMiniMenu.escapeText(meta.id());
        String click = "/gloss board info " + meta.id().replace("'", "");
        String title = DirectorMiniMenu.escapeText(stripColorCodes(meta.title()));
        StringBuilder entry = new StringBuilder();
        entry.append("<hover:show_text:'").append(hover).append("'><click:run_command:'").append(click).append("'>")
                .append("<").append(theme.muted()).append(">⇀</").append(theme.muted()).append("> ")
                .append("<gradient:").append(theme.primaryLeft()).append(":").append(theme.primaryRight()).append(">")
                .append(display).append("</gradient>")
                .append("</click></hover> <").append(theme.description()).append(">").append(title)
                .append("</").append(theme.description()).append(">");
        if (meta.primary()) {
            String marker = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(GlossMessages.BOARD_LIST_PRIMARY, MessageArgs.empty()));
            entry.append(" <").append(theme.optional()).append(">(").append(marker).append(")</")
                    .append(theme.optional()).append(">");
        }
        return entry.toString();
    }

    private static String stripColorCodes(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current == '&' || current == '§') && index + 1 < value.length()) {
                index++;
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }
}
