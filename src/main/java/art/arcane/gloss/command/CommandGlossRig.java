package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.motion.MotionService;
import art.arcane.gloss.rig.BbmodelImporter;
import art.arcane.gloss.rig.RigInstance;
import art.arcane.gloss.rig.RigInstanceDoc;
import art.arcane.gloss.rig.RigService;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Director(name = "rig", aliases = {"rigs"}, descriptionKey = "command.help.rig.root", description = "Place and manage rig instances")
public class CommandGlossRig {
    private static final String LIST_COMMAND = "/gloss rig list";
    private static final String NO_STATE = "-";

    private final Gloss plugin;

    public CommandGlossRig(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.rig.list", description = "List rigs and placed instances; click one to teleport")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page", description = "One-based list page") int page) {
        RigService service = service();
        List<String> rigIds = service == null ? List.of() : new ArrayList<>(service.rigIds());
        if (rigIds.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_LIST_EMPTY);
            return;
        }
        rigIds.sort(String::compareTo);
        GlossCommandMessages.send(sender, GlossMessages.RIG_LIST_RIGS,
            MessageArgument.trusted("count", rigIds.size()),
            MessageArgument.untrusted("value", String.join(", ", rigIds)));
        List<RigInstance> instances = new ArrayList<>(service.instances());
        if (instances.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_INSTANCES_EMPTY);
            return;
        }
        instances.sort(Comparator.comparing(RigInstance::id));
        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(instances.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        DirectorMiniMenu.Theme theme = GlossCommandService.menuTheme();
        String hover = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(GlossMessages.RIG_LIST_HOVER, MessageArgs.empty()));
        List<String> lines = new ArrayList<>();
        GlossCommandPager.appendHeader(lines, LIST_COMMAND, window, theme);
        for (RigInstance instance : instances.subList(window.startIndex(), window.endIndex())) {
            lines.add(renderListEntry(instance, theme, hover));
        }
        GlossCommandPager.appendFooter(lines, window, LIST_COMMAND, theme);
        DirectorMiniMenu.deliver(sender, lines);
    }

    @Director(name = "info", descriptionKey = "command.help.rig.info", description = "Show a rig instance's rig, position and state")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.rig.info.id", description = "Rig instance id") String id) {
        RigInstance instance = find(sender, id);
        if (instance == null) {
            return;
        }
        RigInstanceDoc doc = instance.doc();
        GlossCommandMessages.send(sender, GlossMessages.RIG_INFO_HEADER,
            MessageArgument.untrusted("id", id),
            MessageArgument.untrusted("name", doc.rig()),
            MessageArgument.untrusted("world", doc.world()),
            MessageArgument.trusted("x", (int) Math.floor(doc.x())),
            MessageArgument.trusted("y", (int) Math.floor(doc.y())),
            MessageArgument.trusted("z", (int) Math.floor(doc.z())));
        GlossCommandMessages.send(sender, GlossMessages.RIG_INFO_POSE,
            MessageArgument.trusted("yaw", doc.yaw()),
            MessageArgument.trusted("pitch", doc.pitch()),
            MessageArgument.trusted("scale", doc.scale()),
            MessageArgument.trusted("state", stateOf(instance)));
        GlossCommandMessages.send(sender, GlossMessages.RIG_INFO_PARTS,
            MessageArgument.trusted("count", instance.partCount()),
            MessageArgument.trusted("value", instance.viewerCount()));
        for (Map.Entry<String, Object> entry : instance.machine().vars().entrySet()) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_INFO_VAR,
                MessageArgument.untrusted("name", entry.getKey()),
                MessageArgument.untrusted("value", String.valueOf(entry.getValue())));
        }
    }

    @Director(name = "place", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.rig.place", description = "Place a rig at your location")
    public void place(@Param(name = "player", contextual = true) Player player,
                      @Param(name = "rig", descriptionKey = "command.help.rig.place.rig", description = "Rig document id") String rig,
                      @Param(name = "id", defaultValue = "", descriptionKey = "command.help.rig.place.id", description = "Instance id; blank picks the next free one") String id) {
        if (GlossCommandMessages.denied(player, "gloss.rigs.place")) {
            return;
        }
        RigService service = service();
        if (service == null || service.rig(rig).isEmpty()) {
            GlossCommandMessages.send(player, GlossMessages.RIG_UNKNOWN, MessageArgument.untrusted("id", rig));
            return;
        }
        String requested = id.isBlank() ? null : id;
        try {
            RigInstanceDoc placed = service.place(rig, player.getLocation(), requested);
            String placedId = requested == null ? placedInstanceId(service, rig, placed) : requested;
            GlossCommandMessages.send(player, GlossMessages.RIG_PLACED,
                MessageArgument.untrusted("name", rig),
                MessageArgument.untrusted("id", placedId),
                MessageArgument.untrusted("world", placed.world()),
                MessageArgument.trusted("x", (int) Math.floor(placed.x())),
                MessageArgument.trusted("y", (int) Math.floor(placed.y())),
                MessageArgument.trusted("z", (int) Math.floor(placed.z())));
        } catch (IOException | IllegalArgumentException failure) {
            GlossCommandMessages.send(player, GlossMessages.RIG_PLACE_FAILED,
                MessageArgument.untrusted("name", rig),
                MessageArgument.untrusted("reason", String.valueOf(failure.getMessage())));
        }
    }

    @Director(name = "move", sync = true, descriptionKey = "command.help.rig.move", description = "Offset a rig instance by relative block distances")
    public void move(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.rig.move.id", description = "Rig instance id") String id,
                     @Param(name = "x", defaultValue = "0", descriptionKey = "command.help.rig.move.x", description = "Relative X offset in blocks") double x,
                     @Param(name = "y", defaultValue = "0", descriptionKey = "command.help.rig.move.y", description = "Relative Y offset in blocks") double y,
                     @Param(name = "z", defaultValue = "0", descriptionKey = "command.help.rig.move.z", description = "Relative Z offset in blocks") double z) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.edit")) {
            return;
        }
        RigInstance instance = find(sender, id);
        if (instance == null) {
            return;
        }
        Location origin = instance.origin();
        if (origin == null) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_INSTANCE_MISSING, MessageArgument.untrusted("id", id));
            return;
        }
        moveTo(sender, id, origin.add(x, y, z));
    }

    @Director(name = "here", sync = true, origin = DirectorOrigin.PLAYER, descriptionKey = "command.help.rig.here", description = "Move a rig instance to your location")
    public void here(@Param(name = "player", contextual = true) Player player,
                     @Param(name = "id", descriptionKey = "command.help.rig.here.id", description = "Rig instance id") String id) {
        if (GlossCommandMessages.denied(player, "gloss.rigs.edit")) {
            return;
        }
        if (find(player, id) == null) {
            return;
        }
        moveTo(player, id, player.getLocation());
    }

    @Director(name = "rotate", sync = true, descriptionKey = "command.help.rig.rotate", description = "Set a rig instance's yaw and pitch")
    public void rotate(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.rig.rotate.id", description = "Rig instance id") String id,
                       @Param(name = "yaw", descriptionKey = "command.help.rig.rotate.yaw", description = "Yaw in degrees") double yaw,
                       @Param(name = "pitch", defaultValue = "0", descriptionKey = "command.help.rig.rotate.pitch", description = "Pitch in degrees") double pitch) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.edit")) {
            return;
        }
        if (find(sender, id) == null) {
            return;
        }
        try {
            service().rotate(id, (float) yaw, (float) pitch);
            GlossCommandMessages.send(sender, GlossMessages.RIG_ROTATED,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("yaw", yaw),
                MessageArgument.trusted("pitch", pitch));
        } catch (IOException | IllegalArgumentException failure) {
            sendSaveFailed(sender, id, failure);
        }
    }

    @Director(name = "scale", sync = true, descriptionKey = "command.help.rig.scale", description = "Set a rig instance's uniform scale")
    public void scale(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "id", descriptionKey = "command.help.rig.scale.id", description = "Rig instance id") String id,
                      @Param(name = "scale", descriptionKey = "command.help.rig.scale.scale", description = "Scale multiplier, 0.01 to 64") double scale) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.edit")) {
            return;
        }
        if (find(sender, id) == null) {
            return;
        }
        if (!Double.isFinite(scale) || scale < RigInstanceDoc.MIN_SCALE || scale > RigInstanceDoc.MAX_SCALE) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_SCALE_OUT_OF_RANGE,
                MessageArgument.trusted("value", scale),
                MessageArgument.trusted("minimum", RigInstanceDoc.MIN_SCALE),
                MessageArgument.trusted("maximum", RigInstanceDoc.MAX_SCALE));
            return;
        }
        try {
            service().scale(id, scale);
            GlossCommandMessages.send(sender, GlossMessages.RIG_SCALED,
                MessageArgument.untrusted("id", id),
                MessageArgument.trusted("scale", scale));
        } catch (IOException | IllegalArgumentException failure) {
            sendSaveFailed(sender, id, failure);
        }
    }

    @Director(name = "remove", sync = true, descriptionKey = "command.help.rig.remove", description = "Remove a rig instance")
    public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.rig.remove.id", description = "Rig instance id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.remove")) {
            return;
        }
        if (find(sender, id) == null) {
            return;
        }
        try {
            service().remove(id);
            GlossCommandMessages.send(sender, GlossMessages.RIG_REMOVED, MessageArgument.untrusted("id", id));
        } catch (IOException failure) {
            sendSaveFailed(sender, id, failure);
        }
    }

    @Director(name = "state", sync = true, descriptionKey = "command.help.rig.state", description = "Switch a rig instance to a graph state")
    public void state(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "id", descriptionKey = "command.help.rig.state.id", description = "Rig instance id") String id,
                      @Param(name = "state", descriptionKey = "command.help.rig.state.state", description = "State name from the rig's graph") String state) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.edit")) {
            return;
        }
        if (find(sender, id) == null) {
            return;
        }
        if (!service().setState(id, state)) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_STATE_UNKNOWN,
                MessageArgument.untrusted("id", id),
                MessageArgument.untrusted("state", state));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.RIG_STATE_SET,
            MessageArgument.untrusted("id", id),
            MessageArgument.untrusted("state", state));
    }

    @Director(name = "var", sync = true, descriptionKey = "command.help.rig.var", description = "Set a rig instance variable")
    public void var(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "id", descriptionKey = "command.help.rig.var.id", description = "Rig instance id") String id,
                    @Param(name = "name", descriptionKey = "command.help.rig.var.name", description = "Variable name") String name,
                    @Param(name = "value", descriptionKey = "command.help.rig.var.value", description = "Number, true, false or text") String value) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.edit")) {
            return;
        }
        if (find(sender, id) == null) {
            return;
        }
        service().setVar(id, name, parseValue(value));
        GlossCommandMessages.send(sender, GlossMessages.RIG_VAR_SET,
            MessageArgument.untrusted("name", name),
            MessageArgument.untrusted("id", id),
            MessageArgument.untrusted("value", value));
    }

    @Director(name = "import", sync = true, descriptionKey = "command.help.rig.import", description = "Import a Blockbench model as a rig")
    public void importModel(@Param(name = "sender", contextual = true) CommandSender sender,
                            @Param(name = "file", descriptionKey = "command.help.rig.import.file", description = "Path to a .bbmodel under the Gloss data folder") String file,
                            @Param(name = "id", defaultValue = "", descriptionKey = "command.help.rig.import.id", description = "Rig id; blank uses the file name") String id,
                            @Param(name = "texture", defaultValue = "", descriptionKey = "command.help.rig.import.texture", description = "Custom item as provider:id for every cube") String texture) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.import")) {
            return;
        }
        RigService service = service();
        MotionService motion = plugin.service(MotionService.class);
        if (service == null || motion == null) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_IMPORT_FAILED,
                MessageArgument.untrusted("path", file),
                MessageArgument.untrusted("reason", "rigs are disabled"));
            return;
        }
        Path source = resolve(file);
        if (source == null) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_IMPORT_FAILED,
                MessageArgument.untrusted("path", file),
                MessageArgument.untrusted("reason", "the path must stay inside the Gloss data folder"));
            return;
        }
        String rigId = id.isBlank() ? rigIdFor(source) : id;
        try {
            BbmodelImporter.Result result = new BbmodelImporter(service, motion)
                .importFile(source, rigId, texture.isBlank() ? null : texture);
            GlossCommandMessages.send(sender, GlossMessages.RIG_IMPORT_DONE,
                MessageArgument.untrusted("id", rigId),
                MessageArgument.trusted("count", result.parts()),
                MessageArgument.trusted("value", result.bones()),
                MessageArgument.trusted("kind", result.animations()));
            if (!result.unsupported().isEmpty()) {
                GlossCommandMessages.send(sender, GlossMessages.RIG_IMPORT_UNSUPPORTED,
                    MessageArgument.untrusted("value", String.join(", ", result.unsupported())));
            }
        } catch (IOException | RuntimeException failure) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_IMPORT_FAILED,
                MessageArgument.untrusted("path", file),
                MessageArgument.untrusted("reason", String.valueOf(failure.getMessage())));
        }
    }

    /** Import paths stay inside the Gloss data folder; anything that escapes it is refused. */
    private Path resolve(String file) {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path candidate = root.resolve(file).toAbsolutePath().normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    private static String rigIdFor(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    @Director(name = "reset", sync = true, descriptionKey = "command.help.rig.reset", description = "Restore the shipped rig documents")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "name", defaultValue = "*", descriptionKey = "command.help.arg.reset_name", description = "Name to reset, or * for every shipped default") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.rigs.edit")) {
            return;
        }
        RigService service = service();
        GlossCommandMessages.sendResetResult(sender, "rig", name, service == null ? List.of() : service.resetToDefault(name));
    }

    static Object parseValue(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException notANumber) {
            return trimmed;
        }
    }

    private void moveTo(CommandSender sender, String id, Location target) {
        try {
            service().move(id, target);
            GlossCommandMessages.send(sender, GlossMessages.RIG_MOVED,
                MessageArgument.untrusted("id", id),
                MessageArgument.untrusted("world", target.getWorld() == null ? "?" : target.getWorld().getName()),
                MessageArgument.trusted("x", target.getBlockX()),
                MessageArgument.trusted("y", target.getBlockY()),
                MessageArgument.trusted("z", target.getBlockZ()));
        } catch (IOException | IllegalArgumentException failure) {
            sendSaveFailed(sender, id, failure);
        }
    }

    private static void sendSaveFailed(CommandSender sender, String id, Exception failure) {
        GlossCommandMessages.send(sender, GlossMessages.RIG_SAVE_FAILED,
            MessageArgument.untrusted("id", id),
            MessageArgument.untrusted("reason", String.valueOf(failure.getMessage())));
    }

    private static String placedInstanceId(RigService service, String rig, RigInstanceDoc placed) {
        for (RigInstance instance : service.instances()) {
            if (instance.doc() == placed) {
                return instance.id();
            }
        }
        return rig;
    }

    private static String stateOf(RigInstance instance) {
        String state = instance.state();
        return state == null ? NO_STATE : state;
    }

    private RigInstance find(CommandSender sender, String id) {
        RigService service = service();
        RigInstance instance = service == null ? null : service.instance(id).orElse(null);
        if (instance == null) {
            GlossCommandMessages.send(sender, GlossMessages.RIG_INSTANCE_MISSING, MessageArgument.untrusted("id", id));
        }
        return instance;
    }

    private RigService service() {
        return plugin == null ? null : plugin.service(RigService.class);
    }

    private String renderListEntry(RigInstance instance, DirectorMiniMenu.Theme theme, String hover) {
        RigInstanceDoc doc = instance.doc();
        String display = DirectorMiniMenu.escapeText(instance.id());
        String click = "/gloss rig here " + instance.id().replace("'", "");
        String suffix = DirectorMiniMenu.escapeText(GlossLocalization.globalDirectorText(
            GlossMessages.RIG_LIST_ENTRY,
            GlossLocalization.args(
                MessageArgument.untrusted("name", doc.rig()),
                MessageArgument.untrusted("world", doc.world()),
                MessageArgument.trusted("x", (int) Math.floor(doc.x())),
                MessageArgument.trusted("y", (int) Math.floor(doc.y())),
                MessageArgument.trusted("z", (int) Math.floor(doc.z())),
                MessageArgument.trusted("state", stateOf(instance)))));
        return "<hover:show_text:'" + hover + "'><click:run_command:'" + click + "'>"
            + "<" + theme.muted() + ">⇀</" + theme.muted() + "> "
            + "<gradient:" + theme.primaryLeft() + ":" + theme.primaryRight() + ">" + display + "</gradient>"
            + "</click></hover> <" + theme.description() + ">" + suffix + "</" + theme.description() + ">";
    }
}
