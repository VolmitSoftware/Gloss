package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.marker.MarkerDoc;
import art.arcane.gloss.marker.MarkerRuntime;
import art.arcane.gloss.marker.MarkerService;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Director(name = "marker", aliases = {"markers"}, descriptionKey = "command.help.marker.root",
    description = "World marker tools")
public final class CommandGlossMarker {
    private final Gloss plugin;

    public CommandGlossMarker(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.marker.list", description = "List marker documents")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.markers.list")) {
            return;
        }
        MarkerService markers = service(sender);
        if (markers == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (MarkerRuntime runtime : markers.documents()) {
            ids.add(runtime.id());
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_LIST,
            MessageArgument.trusted("count", ids.size()),
            MessageArgument.trusted("value", String.join(", ", ids)));
    }

    @Director(name = "info", descriptionKey = "command.help.marker.info", description = "Show one marker")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.marker.arg.id",
                         description = "Marker document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.markers.info")) {
            return;
        }
        MarkerService markers = service(sender);
        if (markers == null) {
            return;
        }
        GlossDocument<MarkerDoc> document = markers.registry().get(id);
        if (document == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_MISSING,
                MessageArgument.untrusted("id", id));
            return;
        }
        MarkerAnchor anchor = document.value().anchor();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_INFO,
            MessageArgument.untrusted("id", id),
            MessageArgument.trusted("world", anchor.isPosition() ? anchor.world() : "-"),
            MessageArgument.trusted("x", anchor.isPosition() ? anchor.x() : 0),
            MessageArgument.trusted("y", anchor.isPosition() ? anchor.y() : 0),
            MessageArgument.trusted("z", anchor.isPosition() ? anchor.z() : 0));
    }

    @Director(name = "create", sync = true, descriptionKey = "command.help.marker.create",
        description = "Write a marker document at your position")
    public void create(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.marker.arg.id",
                           description = "Marker document id") String id,
                       @Param(name = "label", defaultValue = "", descriptionKey = "command.help.marker.arg.label",
                           description = "Label shown above the marker") String label) {
        if (GlossCommandMessages.denied(sender, "gloss.markers.create")) {
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Location at = player.getLocation();
        MarkerDoc doc = new MarkerDoc(MarkerDoc.CURRENT_SCHEMA_VERSION, 1L, null,
            MarkerAnchor.position(at.getWorld().getName(), at.getX(), at.getY(), at.getZ()),
            label, null, null, null, null, null, null, null, null, null, null, null);
        try {
            store().write(id, doc);
        } catch (IOException | IllegalArgumentException failure) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_MISSING,
                MessageArgument.untrusted("id", id));
            Gloss.logExceptionStack(false, failure, "Marker %s could not be written.", id);
            return;
        }
        reload();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_CREATED,
            MessageArgument.untrusted("id", id));
    }

    @Director(name = "remove", sync = true, descriptionKey = "command.help.marker.remove",
        description = "Delete a marker document")
    public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.marker.arg.id",
                           description = "Marker document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.markers.remove")) {
            return;
        }
        boolean deleted;
        try {
            deleted = store().delete(id);
        } catch (IOException | IllegalArgumentException failure) {
            deleted = false;
        }
        if (!deleted) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_MISSING,
                MessageArgument.untrusted("id", id));
            return;
        }
        reload();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_REMOVED,
            MessageArgument.untrusted("id", id));
    }

    @Director(name = "here", descriptionKey = "command.help.marker.here",
        description = "Print your position as marker anchor coordinates")
    public void here(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.markers.here")) {
            return;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Location at = player.getLocation();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_MARKER_HERE,
            MessageArgument.trusted("world", at.getWorld().getName()),
            MessageArgument.trusted("x", round(at.getX())),
            MessageArgument.trusted("y", round(at.getY())),
            MessageArgument.trusted("z", round(at.getZ())));
    }

    private DocumentStore<MarkerDoc> store() {
        return new DocumentStore<>(MarkerDoc.KIND, new File(plugin.getDataFolder(), MarkerDoc.KIND),
            MarkerDocReviser.INSTANCE);
    }

    private void reload() {
        MarkerService markers = plugin.service(MarkerService.class);
        if (markers != null) {
            markers.reload();
        }
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

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
