package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.marker.MarkerService;
import art.arcane.gloss.marker.MarkerSpec;
import art.arcane.gloss.marker.PersonalMarkers;
import art.arcane.gloss.waypoint.WaypointDoc;
import art.arcane.gloss.waypoint.WaypointService;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Director(name = "waypoint", aliases = {"waypoints"}, descriptionKey = "command.help.waypoint.root",
    description = "Locator bar waypoint tools")
public final class CommandGlossWaypoint {
    private final Gloss plugin;

    public CommandGlossWaypoint(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "set", sync = true, descriptionKey = "command.help.waypoint.set",
        description = "Save your position as a personal waypoint")
    public void set(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "name", descriptionKey = "command.help.waypoint.arg.name",
                        description = "Waypoint name") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.waypoints.self")) {
            return;
        }
        Player player = requirePlayer(sender);
        MarkerService markers = player == null ? null : service(sender);
        if (markers == null) {
            return;
        }
        if (!markers.personalMarkers().set(player.getUniqueId(), name, player.getLocation())) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_FULL,
                MessageArgument.trusted("count", PersonalMarkers.MAX_PER_PLAYER));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_SAVED,
            MessageArgument.untrusted("name", name));
    }

    @Director(name = "remove", sync = true, descriptionKey = "command.help.waypoint.remove",
        description = "Delete one of your personal waypoints")
    public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "name", descriptionKey = "command.help.waypoint.arg.name",
                           description = "Waypoint name") String name) {
        if (GlossCommandMessages.denied(sender, "gloss.waypoints.self")) {
            return;
        }
        Player player = requirePlayer(sender);
        MarkerService markers = player == null ? null : service(sender);
        if (markers == null) {
            return;
        }
        if (!markers.personalMarkers().remove(player.getUniqueId(), name)) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_MISSING,
                MessageArgument.untrusted("name", name));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_REMOVED,
            MessageArgument.untrusted("name", name));
    }

    @Director(name = "list", descriptionKey = "command.help.waypoint.list",
        description = "List your personal waypoints")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.waypoints.list")) {
            return;
        }
        Player player = requirePlayer(sender);
        MarkerService markers = player == null ? null : service(sender);
        if (markers == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (MarkerSpec spec : markers.personalMarkers().list(player.getUniqueId())) {
            names.add(spec.label());
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_LIST,
            MessageArgument.trusted("count", names.size()),
            MessageArgument.trusted("value", String.join(", ", names)));
    }

    @Director(name = "info", descriptionKey = "command.help.waypoint.info",
        description = "Show one waypoint document")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.waypoint.arg.id",
                         description = "Waypoint document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.waypoints.info")) {
            return;
        }
        WaypointService waypoints = plugin.service(WaypointService.class);
        if (waypoints == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
            return;
        }
        GlossDocument<WaypointDoc> document = waypoints.registry().get(id);
        if (document == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_MISSING,
                MessageArgument.untrusted("name", id));
            return;
        }
        MarkerAnchor anchor = document.value().anchor();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_WAYPOINT_INFO,
            MessageArgument.untrusted("id", id),
            MessageArgument.trusted("world", anchor.isPosition() ? anchor.world() : "-"),
            MessageArgument.trusted("x", anchor.isPosition() ? anchor.x() : 0),
            MessageArgument.trusted("y", anchor.isPosition() ? anchor.y() : 0),
            MessageArgument.trusted("z", anchor.isPosition() ? anchor.z() : 0));
    }

    private MarkerService service(CommandSender sender) {
        MarkerService markers = plugin.service(MarkerService.class);
        if (markers == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
        }
        return markers;
    }

    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_PLAYERS_ONLY);
        return null;
    }
}
