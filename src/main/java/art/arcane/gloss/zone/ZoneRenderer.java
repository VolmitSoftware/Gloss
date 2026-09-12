package art.arcane.gloss.zone;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.particle.ViewerParticles;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draws one viewer's zones. Particle outlines are re-emitted every pass under the viewer's shared
 * budget; wall panels are display entities spawned once and destroyed when the zone leaves range,
 * because a panel that has not moved costs nothing to keep.
 */
public final class ZoneRenderer {
    private final Gloss plugin;
    private final Player viewer;
    private final Map<String, List<DisplayEntity>> walls = new HashMap<>();

    public ZoneRenderer(Gloss plugin, Player viewer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
    }

    public void retireAbsent(List<String> live) {
        List<String> stale = new ArrayList<>();
        for (String zoneId : walls.keySet()) {
            if (!live.contains(zoneId)) {
                stale.add(zoneId);
            }
        }
        for (String zoneId : stale) {
            despawn(zoneId);
        }
    }

    public void destroyAll() {
        for (String zoneId : List.copyOf(walls.keySet())) {
            despawn(zoneId);
        }
    }

    public void render(String zoneId, ZoneDoc doc, ZoneShape shape, ZoneBudget budget, double distance) {
        boolean wallsWanted = wallsWanted(doc, distance) && !plugin.bedrock().isBedrock(viewer);
        if (wallsWanted) {
            spawnWalls(zoneId, doc, shape);
        } else {
            despawn(zoneId);
        }
        if (!wallsWanted || doc.render().mode().equals("hybrid")) {
            emitOutline(doc, shape, budget);
        }
        emitAmbience(doc, shape, budget);
    }

    private boolean wallsWanted(ZoneDoc doc, double distance) {
        return switch (doc.render().mode()) {
            case "walls" -> true;
            case "hybrid" -> distance <= ZoneDoc.HYBRID_WALL_DISTANCE;
            default -> false;
        };
    }

    private void spawnWalls(String zoneId, ZoneDoc doc, ZoneShape shape) {
        if (walls.containsKey(zoneId)) {
            return;
        }
        BlockData data = blockData(doc.render().wallMaterial(), zoneId);
        if (data == null) {
            return;
        }
        World world = viewer.getWorld();
        List<DisplayEntity> spawned = new ArrayList<>();
        for (ZoneWalls.Panel panel : ZoneWalls.panels(shape)) {
            Location at = new Location(world, panel.centre().getX(), panel.centre().getY(),
                panel.centre().getZ());
            DisplayEntity display = DisplayEntity.Builder.blockDisplay(data, at);
            display.scale(new Vector3f((float) panel.width(), (float) panel.height(),
                (float) ZoneWalls.PANEL_THICKNESS));
            display.translation(new Vector3f((float) (-panel.width() / 2.0D),
                (float) (-panel.height() / 2.0D), 0.0F));
            display.leftRotation(rotation(panel.normal()));
            PacketUtils.send(viewer, new ArrayList<>(display.spawn()));
            spawned.add(display);
        }
        walls.put(zoneId, spawned);
    }

    private void despawn(String zoneId) {
        List<DisplayEntity> panels = walls.remove(zoneId);
        if (panels == null || panels.isEmpty()) {
            return;
        }
        int[] ids = new int[panels.size()];
        for (int index = 0; index < panels.size(); index++) {
            ids[index] = panels.get(index).id();
        }
        PacketUtils.send(viewer, DisplayEntity.destroyAll(ids));
    }

    private void emitOutline(ZoneDoc doc, ZoneShape shape, ZoneBudget budget) {
        ViewerParticles.Resolved resolved = ViewerParticles.resolve(doc.render().particle(), doc.render().rgb());
        if (resolved == null) {
            Gloss.warnThrottled("zone-particle:" + doc.render().particle(),
                "Zone particle %s is not a particle this server knows; the outline was skipped.",
                doc.render().particle());
            return;
        }
        Vector eye = viewer.getEyeLocation().toVector();
        World world = viewer.getWorld();
        double viewRange = plugin.cfg().modules().zones().viewRange();
        List<ZoneGeometry.Face> faces = doc.render().facingOnly() ? ZoneGeometry.faces(shape) : List.of();
        for (ZoneGeometry.Segment segment : ZoneGeometry.edges(shape)) {
            if (budget.remaining() <= 0) {
                return;
            }
            if (doc.render().facingOnly() && !visible(faces, segment, eye)) {
                continue;
            }
            for (Vector point : ZoneGeometry.sample(segment, doc.render().spacing())) {
                if (point.distance(eye) > viewRange || budget.take(1) == 0) {
                    continue;
                }
                ViewerParticles.spawn(viewer, resolved, world, point);
            }
        }
    }

    private void emitAmbience(ZoneDoc doc, ZoneShape shape, ZoneBudget budget) {
        ZoneDoc.Ambience ambience = doc.ambience();
        if (!ambience.enabled() || ambience.perViewerPerTick() == 0) {
            return;
        }
        ViewerParticles.Resolved resolved = ViewerParticles.resolve(ambience.particle(), doc.render().rgb());
        if (resolved == null) {
            return;
        }
        int granted = budget.take(ambience.perViewerPerTick());
        if (granted == 0) {
            return;
        }
        World world = viewer.getWorld();
        for (Vector point : ZoneAmbience.points(shape, viewer.getLocation().toVector(),
            ambience.radius(), granted, java.util.concurrent.ThreadLocalRandom.current())) {
            ViewerParticles.spawn(viewer, resolved, world, point);
        }
    }

    /** A segment is drawn when any face it borders faces the eye; a zone seen edge-on keeps its outline. */
    private static boolean visible(List<ZoneGeometry.Face> faces, ZoneGeometry.Segment segment, Vector eye) {
        Vector midpoint = segment.from().add(segment.to()).multiply(0.5D);
        for (ZoneGeometry.Face face : faces) {
            if (ZoneGeometry.facing(face, eye) && face.centre().distance(midpoint) <= maxExtent(face)) {
                return true;
            }
        }
        return false;
    }

    private static double maxExtent(ZoneGeometry.Face face) {
        return Math.hypot(face.width(), face.height()) / 2.0D + 1.0D;
    }

    private static Quaternion4f rotation(Vector normal) {
        return FaceQuaternions.of(normal);
    }

    private BlockData blockData(String material, String zoneId) {
        try {
            return Bukkit.createBlockData(material);
        } catch (IllegalArgumentException failure) {
            Gloss.warnThrottled("zone-wall-material:" + zoneId,
                "Zone %s declares wall material %s, which is not a block; its walls were skipped.",
                zoneId, material);
            return null;
        }
    }
}
