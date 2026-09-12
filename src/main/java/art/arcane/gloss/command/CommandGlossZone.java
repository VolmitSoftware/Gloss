package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.zone.ZoneDoc;
import art.arcane.gloss.zone.ZoneService;
import art.arcane.gloss.zone.ZoneShape;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Director(name = "zone", aliases = {"zones"}, descriptionKey = "command.help.zone.root",
    description = "Zone outline tools")
public final class CommandGlossZone {
    private final Gloss plugin;

    public CommandGlossZone(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "list", descriptionKey = "command.help.zone.list", description = "List zones")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (GlossCommandMessages.denied(sender, "gloss.zones.list")) {
            return;
        }
        ZoneService zones = service(sender);
        if (zones == null) {
            return;
        }
        List<String> ids = zones.ids();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_LIST,
            MessageArgument.trusted("count", ids.size()),
            MessageArgument.trusted("value", String.join(", ", ids)));
    }

    @Director(name = "info", descriptionKey = "command.help.zone.info", description = "Show one zone")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.zone.arg.id",
                         description = "Zone document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.zones.info")) {
            return;
        }
        ZoneService zones = service(sender);
        if (zones == null) {
            return;
        }
        ZoneDoc doc = zones.document(id);
        if (doc == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_MISSING,
                MessageArgument.untrusted("id", id));
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_INFO,
            MessageArgument.untrusted("id", id),
            MessageArgument.trusted("kind", doc.shape().type()),
            MessageArgument.trusted("world", doc.shape().world() == null ? "-" : doc.shape().world()));
    }

    @Director(name = "show", sync = true, descriptionKey = "command.help.zone.show",
        description = "Show a zone's outline to yourself")
    public void show(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.zone.arg.id",
                         description = "Zone document id") String id) {
        toggle(sender, id, true);
    }

    @Director(name = "hide", sync = true, descriptionKey = "command.help.zone.hide",
        description = "Hide a zone's outline from yourself")
    public void hide(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.help.zone.arg.id",
                         description = "Zone document id") String id) {
        toggle(sender, id, false);
    }

    @Director(name = "create", sync = true, descriptionKey = "command.help.zone.create",
        description = "Write a cuboid zone document around your position")
    public void create(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.zone.arg.id",
                           description = "Zone document id") String id,
                       @Param(name = "value", defaultValue = "16", descriptionKey = "command.help.zone.arg.radius",
                           description = "Half-width in blocks") int value) {
        if (GlossCommandMessages.denied(sender, "gloss.zones.create")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_PLAYERS_ONLY);
            return;
        }
        int radius = Math.clamp(value, 1, 512);
        Location at = player.getLocation();
        ZoneShape shape = ZoneShape.cuboid(at.getWorld().getName(),
            new double[]{at.getX() - radius, at.getY() - radius, at.getZ() - radius},
            new double[]{at.getX() + radius, at.getY() + radius, at.getZ() + radius});
        ZoneDoc doc = new ZoneDoc(ZoneDoc.CURRENT_SCHEMA_VERSION, 1L, null, shape, null, null, null, null);
        try {
            store().write(id, doc);
        } catch (IOException | IllegalArgumentException failure) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_MISSING,
                MessageArgument.untrusted("id", id));
            Gloss.logExceptionStack(false, failure, "Zone %s could not be written.", id);
            return;
        }
        reload();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_CREATED,
            MessageArgument.untrusted("id", id));
    }

    @Director(name = "remove", sync = true, descriptionKey = "command.help.zone.remove",
        description = "Delete a zone document")
    public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "id", descriptionKey = "command.help.zone.arg.id",
                           description = "Zone document id") String id) {
        if (GlossCommandMessages.denied(sender, "gloss.zones.remove")) {
            return;
        }
        boolean deleted;
        try {
            deleted = store().delete(id);
        } catch (IOException | IllegalArgumentException failure) {
            deleted = false;
        }
        if (!deleted) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_MISSING,
                MessageArgument.untrusted("id", id));
            return;
        }
        reload();
        GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_REMOVED,
            MessageArgument.untrusted("id", id));
    }

    private void toggle(CommandSender sender, String id, boolean visible) {
        if (GlossCommandMessages.denied(sender, "gloss.zones.toggle")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_PLAYERS_ONLY);
            return;
        }
        ZoneService zones = service(sender);
        if (zones == null) {
            return;
        }
        if (zones.document(id) == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_ZONE_MISSING,
                MessageArgument.untrusted("id", id));
            return;
        }
        zones.show(player.getUniqueId(), id, visible);
        GlossCommandMessages.send(sender,
            visible ? GlossMessages.WORLD_ZONE_SHOWN : GlossMessages.WORLD_ZONE_HIDDEN,
            MessageArgument.untrusted("id", id));
    }

    private DocumentStore<ZoneDoc> store() {
        return new DocumentStore<>(ZoneDoc.KIND, new File(plugin.getDataFolder(), ZoneDoc.KIND),
            ZoneDocReviser.INSTANCE);
    }

    private void reload() {
        ZoneService zones = plugin.service(ZoneService.class);
        if (zones != null) {
            zones.reload();
        }
    }

    private ZoneService service(CommandSender sender) {
        ZoneService zones = plugin.service(ZoneService.class);
        if (zones == null) {
            GlossCommandMessages.send(sender, GlossMessages.WORLD_DISABLED);
        }
        return zones;
    }

    private static final class ZoneDocReviser implements DocumentReviser<ZoneDoc> {
        private static final ZoneDocReviser INSTANCE = new ZoneDocReviser();

        @Override
        public long revisionOf(ZoneDoc value) {
            return value.revision();
        }

        @Override
        public ZoneDoc withRevision(ZoneDoc value, long revision) {
            return new ZoneDoc(value.schemaVersion(), revision, value.show(), value.shape(),
                value.render(), value.ambience(), value.toggle(), value.audience());
        }
    }
}
