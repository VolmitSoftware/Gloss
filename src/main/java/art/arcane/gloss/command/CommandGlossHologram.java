package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.AnchoredHologram;
import art.arcane.gloss.hologram.HologramBaselines;
import art.arcane.gloss.hologram.HologramDoc;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Director(name = "hologram", aliases = {"holo", "h"}, descriptionKey = "command.help.hologram", description = "Create and manage holograms")
public class CommandGlossHologram {
    private static final String LIST_COMMAND = "/gloss hologram list";

    private final Gloss plugin;

    public CommandGlossHologram(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "create", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.hologram.create", description = "Create a hologram at your location")
    public void create(@Param(name = "player", contextual = true) Player player,
                       @Param(name = "id", descriptionKey = "command.help.hologram.create.id", description = "Unique hologram id") String id) {
        if (GlossCommandMessages.denied(player, "gloss.holograms.create")) {
            return;
        }
        if (plugin.holograms().has(id)) {
            GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_EXISTS, MessageArgument.untrusted("id", id));
            return;
        }

        AnchoredHologram hologram = plugin.holograms().create(id, player.getLocation());
        hologram.setLines(HologramBaselines.defaultLines());
        GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_CREATED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "delete", sync = true, descriptionKey = "command.help.hologram.delete", description = "Delete a hologram")
    public void delete(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.hologram.delete.id", description = "Hologram id to delete") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.delete")) {
            return;
        }
        if (!plugin.holograms().has(id)) {
            GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_MISSING, MessageArgument.untrusted("id", id));
            return;
        }

        plugin.holograms().delete(id);
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_DELETED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "addline", sync = true, descriptionKey = "command.help.hologram.addline", description = "Append a line to a hologram")
    public void addline(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "id", descriptionKey = "command.help.hologram.addline.id", description = "Hologram id") String id,
                        @Param(name = "text", descriptionKey = "command.help.hologram.addline.text", description = "Line text; quote it to include spaces") String text) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.edit")) {
            return;
        }
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }

        hologram.addLine(text);
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_LINE_ADDED,
                MessageArgument.trusted("line", hologram.lines().size()),
                MessageArgument.untrusted("id", id));
    }

    @Director(name = "setline", sync = true, descriptionKey = "command.help.hologram.setline", description = "Replace a hologram line")
    public void setline(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "id", descriptionKey = "command.help.hologram.setline.id", description = "Hologram id") String id,
                        @Param(name = "line", descriptionKey = "command.help.hologram.setline.line", description = "Line number, starting at 1") int line,
                        @Param(name = "text", descriptionKey = "command.help.hologram.setline.text", description = "Line text; quote it to include spaces") String text) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.edit")) {
            return;
        }
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }
        if (outOfRange(sender, hologram, id, line)) {
            return;
        }

        hologram.setLine(line - 1, text);
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_LINE_SET,
                MessageArgument.trusted("line", line),
                MessageArgument.untrusted("id", id));
    }

    @Director(name = "removeline", sync = true, descriptionKey = "command.help.hologram.removeline", description = "Remove a hologram line")
    public void removeline(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "id", descriptionKey = "command.help.hologram.removeline.id", description = "Hologram id") String id,
                           @Param(name = "line", descriptionKey = "command.help.hologram.removeline.line", description = "Line number, starting at 1") int line) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.edit")) {
            return;
        }
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }
        if (outOfRange(sender, hologram, id, line)) {
            return;
        }

        hologram.removeLine(line - 1);
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_LINE_REMOVED,
                MessageArgument.trusted("line", line),
                MessageArgument.untrusted("id", id));
    }

    @Director(name = "clear", sync = true, descriptionKey = "command.help.hologram.clear", description = "Remove every line from a hologram")
    public void clear(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "id", descriptionKey = "command.help.hologram.clear.id", description = "Hologram id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.edit")) {
            return;
        }
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }

        hologram.clearLines();
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_CLEARED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "movehere", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.hologram.movehere", description = "Move a hologram to your location")
    public void movehere(@Param(name = "player", contextual = true) Player player,
                         @Param(name = "id", descriptionKey = "command.help.hologram.movehere.id", description = "Hologram id") String id) {
        if (GlossCommandMessages.denied(player, "gloss.holograms.move")) {
            return;
        }
        AnchoredHologram hologram = find(player, id);
        if (hologram == null) {
            return;
        }

        hologram.teleport(player.getLocation());
        GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_MOVED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "move", sync = true, descriptionKey = "command.help.hologram.move", description = "Offset a hologram by relative block distances")
    public void move(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.hologram.move.id", description = "Hologram id") String id,
                     @Param(name = "x", defaultValue = "0", descriptionKey = "command.help.hologram.move.x", description = "Relative X offset in blocks") double x,
                     @Param(name = "y", defaultValue = "0", descriptionKey = "command.help.hologram.move.y", description = "Relative Y offset in blocks") double y,
                     @Param(name = "z", defaultValue = "0", descriptionKey = "command.help.hologram.move.z", description = "Relative Z offset in blocks") double z) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.move")) {
            return;
        }
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }

        Location target = hologram.location().clone().add(x, y, z);
        hologram.teleport(target);
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_OFFSET,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("x", x),
                MessageArgument.trusted("y", y),
                MessageArgument.trusted("z", z));
    }

    @Director(name = "tp", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.hologram.tp", description = "Teleport yourself to a hologram")
    public void tp(@Param(name = "player", contextual = true) Player player,
                   @Param(name = "id", descriptionKey = "command.help.hologram.tp.id", description = "Hologram id") String id) {
        if (GlossCommandMessages.denied(player, "gloss.holograms.teleport")) {
            return;
        }
        AnchoredHologram hologram = find(player, id);
        if (hologram == null) {
            return;
        }

        plugin.scheduler().teleport(player, hologram.location());
        GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_TELEPORTED, MessageArgument.untrusted("id", id));
    }

    @Director(name = "list", descriptionKey = "command.help.hologram.list", description = "List holograms; click one to teleport")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page", description = "One-based list page") int page) {
        List<AnchoredHologram> holograms = plugin.holograms().all();
        if (holograms.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_LIST_EMPTY);
            return;
        }

        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(holograms.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        String hover = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(GlossMessages.HOLOGRAM_LIST_HOVER, MessageArgs.empty()));
        List<String> lines = new ArrayList<>();
        GlossCommandPager.appendHeader(lines, LIST_COMMAND, window, theme);
        for (AnchoredHologram hologram : holograms.subList(window.startIndex(), window.endIndex())) {
            lines.add(renderListEntry(hologram, theme, hover));
        }
        GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", descriptionKey = "command.help.hologram.info", description = "Show a hologram's location and lines")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.hologram.info.id", description = "Hologram id") String id) {
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }

        Location location = hologram.location();
        String world = location.getWorld() == null ? "unknown" : location.getWorld().getName();
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_INFO_HEADER,
                MessageArgument.untrusted("id", id),
                MessageArgument.untrusted("world", world),
                MessageArgument.trusted("x", location.getBlockX()),
                MessageArgument.trusted("y", location.getBlockY()),
                MessageArgument.trusted("z", location.getBlockZ()));
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_INFO_ORIENTATION,
                MessageArgument.trusted("billboard", hologram.billboard()),
                MessageArgument.trusted("yaw", hologram.yaw()),
                MessageArgument.trusted("pitch", hologram.pitch()));
        List<String> lines = hologram.lines();
        for (int index = 0; index < lines.size(); index++) {
            GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_INFO_LINE,
                    MessageArgument.trusted("line", index + 1),
                    MessageArgument.trusted("text", lines.get(index)));
        }
    }

    @Director(name = "orient", sync = true, descriptionKey = "command.help.hologram.orient", description = "Set how a hologram turns to face viewers")
    public void orient(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.hologram.orient.id", description = "Hologram id") String id,
                       @Param(name = "billboard", defaultValue = "CENTER", descriptionKey = "command.help.hologram.orient.billboard", description = "CENTER, VERTICAL, HORIZONTAL or FIXED") String billboard,
                       @Param(name = "yaw", defaultValue = "0", descriptionKey = "command.help.hologram.orient.yaw", description = "Facing yaw in degrees, -180 to 180") double yaw,
                       @Param(name = "pitch", defaultValue = "0", descriptionKey = "command.help.hologram.orient.pitch", description = "Facing pitch in degrees, -90 to 90") double pitch) {
        if (GlossCommandMessages.denied(sender, "gloss.holograms.edit")) {
            return;
        }
        AnchoredHologram hologram = find(sender, id);
        if (hologram == null) {
            return;
        }

        String mode = HologramDoc.normalizeBillboard(billboard);
        if (mode == null) {
            GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_BILLBOARD_UNKNOWN,
                    MessageArgument.untrusted("billboard", billboard),
                    MessageArgument.trusted("options", String.join(", ", HologramDoc.BILLBOARDS)));
            return;
        }
        if (angleOutOfRange(sender, "yaw", yaw, HologramDoc.MAX_YAW_DEGREES)
                || angleOutOfRange(sender, "pitch", pitch, HologramDoc.MAX_PITCH_DEGREES)) {
            return;
        }

        hologram.setOrientation(mode, yaw, pitch);
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_ORIENTED,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("billboard", mode),
                MessageArgument.trusted("yaw", yaw),
                MessageArgument.trusted("pitch", pitch));
    }

    @Director(name = "rendertext", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.hologram.rendertext", description = "Rasterize text into block-art hologram lines")
    public void rendertext(@Param(name = "player", contextual = true) Player player,
                           @Param(name = "id", descriptionKey = "command.help.hologram.rendertext.id", description = "Unique hologram id") String id,
                           @Param(name = "text", descriptionKey = "command.help.hologram.rendertext.text", description = "Text to rasterize; quote it to include spaces") String text,
                           @Param(name = "scale", defaultValue = "1", descriptionKey = "command.help.hologram.rendertext.scale", description = "Font scale multiplier") double scale) {
        if (GlossCommandMessages.denied(player, "gloss.holograms.create")) {
            return;
        }
        if (plugin.holograms().has(id)) {
            GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_EXISTS, MessageArgument.untrusted("id", id));
            return;
        }

        List<String> rows = TextArtRenderer.render(text, scale, plugin.cfg().holograms().textArtMaxWidth());
        if (rows.isEmpty()) {
            GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_RENDER_EMPTY);
            return;
        }

        AnchoredHologram hologram = plugin.holograms().create(id, player.getLocation());
        hologram.setLines(rows);
        GlossCommandMessages.send(player, GlossMessages.HOLOGRAM_RENDERED,
                MessageArgument.trusted("rows", rows.size()),
                MessageArgument.untrusted("id", id));
    }

    private AnchoredHologram find(CommandSender sender, String id) {
        AnchoredHologram hologram = plugin.holograms().get(id);
        if (hologram == null) {
            GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_MISSING, MessageArgument.untrusted("id", id));
        }
        return hologram;
    }

    private boolean outOfRange(CommandSender sender, AnchoredHologram hologram, String id, int line) {
        int count = hologram.lines().size();
        if (line >= 1 && line <= count) {
            return false;
        }
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_LINE_OUT_OF_RANGE,
                MessageArgument.trusted("line", line),
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("count", count));
        return true;
    }

    private boolean angleOutOfRange(CommandSender sender, String axis, double angle, double limit) {
        if (HologramDoc.angleInRange(angle, limit)) {
            return false;
        }
        GlossCommandMessages.send(sender, GlossMessages.HOLOGRAM_ANGLE_OUT_OF_RANGE,
                MessageArgument.trusted("axis", axis),
                MessageArgument.trusted("value", angle),
                MessageArgument.trusted("limit", limit));
        return true;
    }

    private String renderListEntry(AnchoredHologram hologram, DirectorMiniMenu.Theme theme, String hover) {
        String display = DirectorMiniMenu.escapeText(hologram.id());
        String click = "/gloss hologram tp " + hologram.id().replace("'", "");
        String suffix = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(
                GlossMessages.HOLOGRAM_LIST_LINES,
                GlossLocalization.args(MessageArgument.trusted("count", hologram.lines().size()))));
        return "<hover:show_text:'" + hover + "'><click:run_command:'" + click + "'>"
                + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
                + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + display + "</gradient>"
                + "</click></hover> <" + theme.description() + ">" + suffix + "</" + theme.description() + ">";
    }
}
