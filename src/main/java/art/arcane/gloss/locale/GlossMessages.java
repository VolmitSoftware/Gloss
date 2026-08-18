package art.arcane.gloss.locale;

import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.util.ArrayList;
import java.util.List;

public final class GlossMessages {
    public static final String ENGLISH_LOCALE = VolmitLocales.ENGLISH;
    private static final String PREFIX = "&8[&dGloss&8] ";

    private static final List<MessageKey> KEYS = new ArrayList<>();

    public static final TextKey HELP_ROOT = text("command.help.root", "Gloss command root");
    public static final TextKey HELP_HOLOGRAM = text("command.help.hologram", "Create and manage holograms");
    public static final TextKey HELP_HOLOGRAM_CREATE = text("command.help.hologram.create", "Create a hologram at your location");
    public static final TextKey HELP_HOLOGRAM_CREATE_ID = text("command.help.hologram.create.id", "Unique hologram id");
    public static final TextKey HELP_HOLOGRAM_DELETE = text("command.help.hologram.delete", "Delete a hologram");
    public static final TextKey HELP_HOLOGRAM_DELETE_ID = text("command.help.hologram.delete.id", "Hologram id to delete");
    public static final TextKey HELP_HOLOGRAM_ADDLINE = text("command.help.hologram.addline", "Append a line to a hologram");
    public static final TextKey HELP_HOLOGRAM_ADDLINE_ID = text("command.help.hologram.addline.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_ADDLINE_TEXT = text("command.help.hologram.addline.text", "Line text; quote it to include spaces");
    public static final TextKey HELP_HOLOGRAM_SETLINE = text("command.help.hologram.setline", "Replace a hologram line");
    public static final TextKey HELP_HOLOGRAM_SETLINE_ID = text("command.help.hologram.setline.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_SETLINE_LINE = text("command.help.hologram.setline.line", "Line number, starting at 1");
    public static final TextKey HELP_HOLOGRAM_SETLINE_TEXT = text("command.help.hologram.setline.text", "Line text; quote it to include spaces");
    public static final TextKey HELP_HOLOGRAM_REMOVELINE = text("command.help.hologram.removeline", "Remove a hologram line");
    public static final TextKey HELP_HOLOGRAM_REMOVELINE_ID = text("command.help.hologram.removeline.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_REMOVELINE_LINE = text("command.help.hologram.removeline.line", "Line number, starting at 1");
    public static final TextKey HELP_HOLOGRAM_CLEAR = text("command.help.hologram.clear", "Remove every line from a hologram");
    public static final TextKey HELP_HOLOGRAM_CLEAR_ID = text("command.help.hologram.clear.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_MOVEHERE = text("command.help.hologram.movehere", "Move a hologram to your location");
    public static final TextKey HELP_HOLOGRAM_MOVEHERE_ID = text("command.help.hologram.movehere.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_MOVE = text("command.help.hologram.move", "Offset a hologram by relative block distances");
    public static final TextKey HELP_HOLOGRAM_MOVE_ID = text("command.help.hologram.move.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_MOVE_X = text("command.help.hologram.move.x", "Relative X offset in blocks");
    public static final TextKey HELP_HOLOGRAM_MOVE_Y = text("command.help.hologram.move.y", "Relative Y offset in blocks");
    public static final TextKey HELP_HOLOGRAM_MOVE_Z = text("command.help.hologram.move.z", "Relative Z offset in blocks");
    public static final TextKey HELP_HOLOGRAM_TP = text("command.help.hologram.tp", "Teleport yourself to a hologram");
    public static final TextKey HELP_HOLOGRAM_TP_ID = text("command.help.hologram.tp.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_LIST = text("command.help.hologram.list", "List holograms; click one to teleport");
    public static final TextKey HELP_HOLOGRAM_INFO = text("command.help.hologram.info", "Show a hologram's location and lines");
    public static final TextKey HELP_HOLOGRAM_INFO_ID = text("command.help.hologram.info.id", "Hologram id");
    public static final TextKey HELP_HOLOGRAM_RENDERTEXT = text("command.help.hologram.rendertext", "Rasterize text into block-art hologram lines");
    public static final TextKey HELP_HOLOGRAM_RENDERTEXT_ID = text("command.help.hologram.rendertext.id", "Unique hologram id");
    public static final TextKey HELP_HOLOGRAM_RENDERTEXT_TEXT = text("command.help.hologram.rendertext.text", "Text to rasterize; quote it to include spaces");
    public static final TextKey HELP_HOLOGRAM_RENDERTEXT_SCALE = text("command.help.hologram.rendertext.scale", "Font scale multiplier");

    public static final TextKey HELP_BOARD = text("command.help.board", "Create and manage scoreboards");
    public static final TextKey HELP_BOARD_CREATE = text("command.help.board.create", "Create a scoreboard");
    public static final TextKey HELP_BOARD_CREATE_ID = text("command.help.board.create.id", "Unique board id");
    public static final TextKey HELP_BOARD_DELETE = text("command.help.board.delete", "Delete a scoreboard");
    public static final TextKey HELP_BOARD_DELETE_ID = text("command.help.board.delete.id", "Board id to delete");
    public static final TextKey HELP_BOARD_TITLE = text("command.help.board.title", "Set a scoreboard title");
    public static final TextKey HELP_BOARD_TITLE_ID = text("command.help.board.title.id", "Board id");
    public static final TextKey HELP_BOARD_TITLE_TEXT = text("command.help.board.title.text", "Title text; quote it to include spaces");
    public static final TextKey HELP_BOARD_ADDLINE = text("command.help.board.addline", "Append a line to a scoreboard");
    public static final TextKey HELP_BOARD_ADDLINE_ID = text("command.help.board.addline.id", "Board id");
    public static final TextKey HELP_BOARD_ADDLINE_TEXT = text("command.help.board.addline.text", "Line text; quote it to include spaces");
    public static final TextKey HELP_BOARD_SETLINE = text("command.help.board.setline", "Replace a scoreboard line");
    public static final TextKey HELP_BOARD_SETLINE_ID = text("command.help.board.setline.id", "Board id");
    public static final TextKey HELP_BOARD_SETLINE_LINE = text("command.help.board.setline.line", "Line number, starting at 1");
    public static final TextKey HELP_BOARD_SETLINE_TEXT = text("command.help.board.setline.text", "Line text; quote it to include spaces");
    public static final TextKey HELP_BOARD_REMOVELINE = text("command.help.board.removeline", "Remove a scoreboard line");
    public static final TextKey HELP_BOARD_REMOVELINE_ID = text("command.help.board.removeline.id", "Board id");
    public static final TextKey HELP_BOARD_REMOVELINE_LINE = text("command.help.board.removeline.line", "Line number, starting at 1");
    public static final TextKey HELP_BOARD_SHOW = text("command.help.board.show", "Show a scoreboard to yourself");
    public static final TextKey HELP_BOARD_SHOW_ID = text("command.help.board.show.id", "Board id");
    public static final TextKey HELP_BOARD_HIDE = text("command.help.board.hide", "Hide your scoreboard");
    public static final TextKey HELP_BOARD_PRIMARY = text("command.help.board.primary", "Mark a scoreboard as the primary default board");
    public static final TextKey HELP_BOARD_PRIMARY_ID = text("command.help.board.primary.id", "Board id");
    public static final TextKey HELP_BOARD_PRIMARY_ENABLED = text("command.help.board.primary.enabled", "true to mark primary, false to unmark");
    public static final TextKey HELP_BOARD_PERMISSION = text("command.help.board.permission", "Set the permission required to see a scoreboard");
    public static final TextKey HELP_BOARD_PERMISSION_ID = text("command.help.board.permission.id", "Board id");
    public static final TextKey HELP_BOARD_PERMISSION_NODE = text("command.help.board.permission.node", "Permission node; use default to clear");
    public static final TextKey HELP_BOARD_LIST = text("command.help.board.list", "List scoreboards");
    public static final TextKey HELP_BOARD_INFO = text("command.help.board.info", "Show a scoreboard's title, lines and settings");
    public static final TextKey HELP_BOARD_INFO_ID = text("command.help.board.info.id", "Board id");

    public static final TextKey HELP_EMOJI = text("command.help.emoji", "List enabled emoji; click one to insert it into chat");
    public static final TextKey HELP_EMOJI_PAGE = text("command.help.emoji.page", "Page number");
    public static final TextKey HELP_ANIMATIONS = text("command.help.animations", "List animation names");
    public static final TextKey HELP_GROUP = text("command.help.group", "Inspect Gloss display groups");
    public static final TextKey HELP_GROUP_LIST = text("command.help.group.list", "List display groups");
    public static final TextKey HELP_GROUP_INFO = text("command.help.group.info", "Show a group's tablist name and default board");
    public static final TextKey HELP_GROUP_INFO_NAME = text("command.help.group.info.name", "Group name");
    public static final TextKey HELP_STATUS = text("command.help.status", "Show terse runtime counts");
    public static final TextKey HELP_RELOAD = text("command.help.reload", "Reload Gloss configuration and services");
    public static final TextKey HELP_VERSION = text("command.help.version", "Show the Gloss version and supported Minecraft range");

    public static final TextKey COMMAND_NO_PERMISSION = text("command.error.no_permission", PREFIX + "&cYou do not have permission.");
    public static final TextKey COMMAND_NO_PERMISSION_USE = text("command.error.no_permission_use", PREFIX + "&cYou do not have permission to use that command.");
    public static final TextKey COMMAND_USAGE_HELP = text("command.error.usage", "&7Usage: &f/{command} help");

    public static final TextKey HOLOGRAM_EXISTS = text("command.hologram.exists", PREFIX + "&cHologram '&f{id}&c' already exists.");
    public static final TextKey HOLOGRAM_MISSING = text("command.hologram.missing", PREFIX + "&cNo hologram named '&f{id}&c'.");
    public static final TextKey HOLOGRAM_CREATED = text("command.hologram.created", PREFIX + "&aCreated hologram &f{id}&a.");
    public static final TextKey HOLOGRAM_DELETED = text("command.hologram.deleted", PREFIX + "&aDeleted hologram &f{id}&a.");
    public static final TextKey HOLOGRAM_LINE_ADDED = text("command.hologram.line_added", PREFIX + "&aAdded line &f{line}&a to &f{id}&a.");
    public static final TextKey HOLOGRAM_LINE_SET = text("command.hologram.line_set", PREFIX + "&aSet line &f{line}&a on &f{id}&a.");
    public static final TextKey HOLOGRAM_LINE_REMOVED = text("command.hologram.line_removed", PREFIX + "&aRemoved line &f{line}&a from &f{id}&a.");
    public static final TextKey HOLOGRAM_LINE_OUT_OF_RANGE = text("command.hologram.line_out_of_range", PREFIX + "&cLine {line} is out of range; &f{id}&c has {count} lines.");
    public static final TextKey HOLOGRAM_CLEARED = text("command.hologram.cleared", PREFIX + "&aCleared all lines on &f{id}&a.");
    public static final TextKey HOLOGRAM_MOVED = text("command.hologram.moved", PREFIX + "&aMoved &f{id}&a to your location.");
    public static final TextKey HOLOGRAM_OFFSET = text("command.hologram.offset", PREFIX + "&aMoved &f{id}&a by &f{x}&a, &f{y}&a, &f{z}&a.");
    public static final TextKey HOLOGRAM_TELEPORTED = text("command.hologram.teleported", PREFIX + "&aTeleported to &f{id}&a.");
    public static final TextKey HOLOGRAM_LIST_EMPTY = text("command.hologram.list_empty", PREFIX + "&7No holograms exist yet.");
    public static final TextKey HOLOGRAM_LIST_HOVER = text("command.hologram.list.hover", "Click to teleport");
    public static final TextKey HOLOGRAM_LIST_LINES = text("command.hologram.list.lines", "{count} lines");
    public static final TextKey HOLOGRAM_INFO_HEADER = text("command.hologram.info.header", PREFIX + "&d{id} &7at &f{world} {x}, {y}, {z}");
    public static final TextKey HOLOGRAM_INFO_LINE = text("command.hologram.info.line", " &8{line}. &f{text}");
    public static final TextKey HOLOGRAM_RENDERED = text("command.hologram.rendered", PREFIX + "&aRendered &f{rows}&a rows of text art into &f{id}&a.");
    public static final TextKey HOLOGRAM_RENDER_EMPTY = text("command.hologram.render_empty", PREFIX + "&cThat text rendered no visible pixels.");

    public static final TextKey BOARD_EXISTS = text("command.board.exists", PREFIX + "&cBoard '&f{id}&c' already exists.");
    public static final TextKey BOARD_MISSING = text("command.board.missing", PREFIX + "&cNo board named '&f{id}&c'.");
    public static final TextKey BOARD_CREATED = text("command.board.created", PREFIX + "&aCreated board &f{id}&a.");
    public static final TextKey BOARD_DELETED = text("command.board.deleted", PREFIX + "&aDeleted board &f{id}&a.");
    public static final TextKey BOARD_TITLE_SET = text("command.board.title_set", PREFIX + "&aSet the title of &f{id}&a.");
    public static final TextKey BOARD_LINE_ADDED = text("command.board.line_added", PREFIX + "&aAdded line &f{line}&a to &f{id}&a.");
    public static final TextKey BOARD_LINE_SET = text("command.board.line_set", PREFIX + "&aSet line &f{line}&a on &f{id}&a.");
    public static final TextKey BOARD_LINE_REMOVED = text("command.board.line_removed", PREFIX + "&aRemoved line &f{line}&a from &f{id}&a.");
    public static final TextKey BOARD_LINE_OUT_OF_RANGE = text("command.board.line_out_of_range", PREFIX + "&cLine {line} is out of range; &f{id}&c has {count} lines.");
    public static final TextKey BOARD_SHOWN = text("command.board.shown", PREFIX + "&aShowing board &f{id}&a.");
    public static final TextKey BOARD_HIDDEN = text("command.board.hidden", PREFIX + "&aYour scoreboard is hidden.");
    public static final TextKey BOARD_PRIMARY_SET = text("command.board.primary_set", PREFIX + "&aBoard &f{id}&a primary: &f{enabled}&a.");
    public static final TextKey BOARD_PERMISSION_SET = text("command.board.permission_set", PREFIX + "&aBoard &f{id}&a permission: &f{node}&a.");
    public static final TextKey BOARD_PERMISSION_CLEARED = text("command.board.permission_cleared", PREFIX + "&aBoard &f{id}&a permission cleared.");
    public static final TextKey BOARD_LIST_EMPTY = text("command.board.list_empty", PREFIX + "&7No boards exist yet.");
    public static final TextKey BOARD_LIST_HOVER = text("command.board.list.hover", "Click for details");
    public static final TextKey BOARD_LIST_PRIMARY = text("command.board.list.primary", "primary");
    public static final TextKey BOARD_INFO_HEADER = text("command.board.info.header", PREFIX + "&d{id} &7titled &f{title}");
    public static final TextKey BOARD_INFO_META = text("command.board.info.meta", "&7Primary: &f{primary} &8| &7Permission: &f{permission}");
    public static final TextKey BOARD_INFO_LINE = text("command.board.info.line", " &8{line}. &f{text}");
    public static final TextKey BOARD_PERMISSION_NONE = text("command.board.info.permission_none", "default");

    public static final TextKey EMOJI_EMPTY = text("command.emoji.empty", PREFIX + "&7No emoji are enabled.");
    public static final TextKey EMOJI_HOVER = text("command.emoji.hover", "Click to insert into chat");
    public static final TextKey ANIMATIONS_EMPTY = text("command.animations.empty", PREFIX + "&7No animations are loaded.");
    public static final TextKey ANIMATIONS_ENTRY = text("command.animations.entry", "&8- &d{name}");
    public static final TextKey GROUP_EMPTY = text("command.group.empty", PREFIX + "&7No groups are defined.");
    public static final TextKey GROUP_MISSING = text("command.group.missing", PREFIX + "&cNo group named '&f{name}&c'.");
    public static final TextKey GROUP_LIST_ENTRY = text("command.group.list.entry", "&8- &d{name}");
    public static final TextKey GROUP_INFO = text("command.group.info", PREFIX + "&d{name} &7tablist: &f{tablist} &8| &7board: &f{board}");

    public static final TextKey STATUS_HOLOGRAMS = text("command.status.holograms", "&7Holograms: &f{count} &8({temporary} temporary, {entities} entities)");
    public static final TextKey STATUS_BOARDS = text("command.status.boards", "&7Boards: &f{count}");
    public static final TextKey STATUS_EMOJI = text("command.status.emoji", "&7Emoji: &f{enabled}&8/&f{total}");
    public static final TextKey STATUS_ANIMATIONS = text("command.status.animations", "&7Animations: &f{count}");
    public static final TextKey STATUS_EFFECTS = text("command.status.effects", "&7Bubbles: &f{bubbles} &8| &7Indicators: &f{indicators} &8| &7Drops: &f{drops}");

    public static final TextKey RELOAD_DONE = text("command.reload.done", PREFIX + "&aReloaded Gloss configuration and services.");
    public static final TextKey VERSION_LINE = text("command.version.line", PREFIX + "&dGloss &f{version} &8| &7MC Support: &f{mc}");

    private GlossMessages() {
    }

    public static MessageCatalog catalog() {
        return MessageCatalog.builder(ENGLISH_LOCALE)
                .addAll(DirectorMessages.keys())
                .addAll(KEYS)
                .build();
    }

    private static TextKey text(String id, String english) {
        TextKey key = TextKey.of(id, english);
        KEYS.add(key);
        return key;
    }
}
