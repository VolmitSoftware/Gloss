package art.arcane.gloss.marker;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.config.components.DecoComponentData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.MenuSessionOptions;
import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.menu.action.NavigationResult;
import art.arcane.gloss.util.common.DisplayEntity;
import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The entities one viewer sees for one marker: a label hologram, an optional beam block display,
 * an optional off-screen edge indicator and an optional trail. Renders are diffed by marker id, so
 * a marker that stays selected between passes keeps its entities instead of blinking.
 */
public final class MarkerRenderer {
    private static final long FOREVER_MS = Long.MAX_VALUE;

    static final class Render {
        private TemporaryHologram label;
        private TemporaryHologram edge;
        private UUID beam;
        private DisplayEntityGroup beamGroup;
        private MenuSession icon;
        private MenuIconData iconData;
        private String labelText = "";
        private MarkerSpec spec;
        private double scale = 1.0D;

        void destroy(Player viewer) {
            if (label != null) {
                label.destroy();
                label = null;
            }
            if (edge != null) {
                edge.destroy();
                edge = null;
            }
            if (icon != null) {
                icon.close();
                icon = null;
            }
            if (beam != null) {
                try {
                    beamGroup.close();
                } catch (RuntimeException failure) {
                    DisplayEntityManager.retire(Gloss.instance, beamGroup);
                    throw failure;
                }
                beam = null;
            }
        }

        int entityCount() {
            return (label == null ? 0 : 1) + (edge == null ? 0 : 1) + (beamGroup == null ? 0 : beamGroup.visibleCount())
                + (icon == null ? 0 : icon.displayGroup().visibleCount());
        }
    }

    private final Gloss plugin;
    private final Player viewer;
    private final Map<String, Render> renders = new HashMap<>();

    public MarkerRenderer(Gloss plugin, Player viewer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
    }

    public int entityCount() {
        int total = 0;
        for (Render render : renders.values()) {
            total += render.entityCount();
        }
        return total;
    }

    /** Retires every render whose marker is no longer selected. */
    public void retireAbsent(List<MarkerCandidate> selected) {
        List<String> live = new ArrayList<>(selected.size());
        for (MarkerCandidate candidate : selected) {
            live.add(candidate.id());
        }
        renders.keySet().removeIf(id -> {
            if (live.contains(id)) {
                return false;
            }
            renders.get(id).destroy(viewer);
            return true;
        });
    }

    public void destroyAll() {
        for (Render render : renders.values()) {
            render.destroy(viewer);
        }
        renders.clear();
    }

    public void apply(MarkerCandidate candidate, Location anchor, String labelText, double scale,
                      EdgeIndicatorMath.Indicator indicator) {
        Render render = renders.computeIfAbsent(candidate.id(), ignored -> new Render());
        MarkerSpec spec = candidate.spec();
        if (render.spec != null && (!Objects.equals(render.spec.style(), spec.style())
            || !Objects.equals(render.spec.box(), spec.box())
            || !Objects.equals(render.spec.beam(), spec.beam())
            || !Objects.equals(render.spec.edge(), spec.edge()))) {
            render.destroy(viewer);
        }
        render.spec = spec;
        VisibilityGovernor.Tier tier = plugin.governor().tier(viewer, VisibilityGovernor.Surface.MARKER,
            candidate.distance() * candidate.distance());
        if (tier == VisibilityGovernor.Tier.CULLED) {
            render.destroy(viewer);
            return;
        }
        applyLabel(render, candidate, anchor, labelText, scale);
        if (tier == VisibilityGovernor.Tier.MINIMAL) {
            if (render.icon != null) {
                render.icon.close();
                render.icon = null;
            }
        } else {
            applyIcon(render, candidate, anchor, scale);
        }
        applyBeam(render, candidate, anchor, tier == VisibilityGovernor.Tier.FULL);
        applyEdge(render, candidate, tier == VisibilityGovernor.Tier.FULL ? indicator : null);
    }

    Render render(String markerId) {
        return renders.get(markerId);
    }

    private void applyLabel(Render render, MarkerCandidate candidate, Location anchor, String labelText,
                            double scale) {
        if (labelText.isBlank()) {
            if (render.label != null) {
                render.label.destroy();
                render.label = null;
            }
            return;
        }
        if (render.label == null) {
            render.label = create("gloss-marker:" + candidate.id(), anchor);
            render.label.setStyle(candidate.spec().style());
            render.label.setBox(candidate.spec().box());
            render.labelText = "";
            render.scale = Double.NaN;
        }
        render.label.bindPosition(viewer, () -> anchor);
        if (!labelText.equals(render.labelText)) {
            render.label.setRenderedLines(List.of(labelText));
            render.labelText = labelText;
        }
        if (render.scale != scale) {
            double applied = scale;
            render.label.bindPresentation(viewer,
                () -> new HologramPresentation(applied, applied, applied, 0, 0, 0, 1));
            render.scale = scale;
        }
    }

    private void applyIcon(Render render, MarkerCandidate candidate, Location anchor, double scale) {
        MenuIconData icon = candidate.spec().icon();
        if (!Objects.equals(render.iconData, icon)) {
            if (render.icon != null) {
                render.icon.close();
                render.icon = null;
            }
            render.iconData = icon;
        }
        if (icon == null) {
            return;
        }
        Location position = anchor.clone().add(0.0D, 0.75D * scale, 0.0D);
        position.setYaw(viewer.getEyeLocation().getYaw());
        if (render.icon == null) {
            MenuDefinitionData definition = new MenuDefinitionData(new Vector(), false, false, null,
                false, false, List.of(new MenuComponentData("icon", new Vector(),
                    new DecoComponentData(icon), null)), List.of(), null, Map.of(), List.of());
            definition.setId("marker:" + candidate.id());
            MenuTransform transform = new MenuTransform(position, new Vector(), position.getYaw(),
                0F, 0F, (float) scale);
            render.icon = new MenuSession(definition, viewer,
                MenuSessionOptions.positioned(transform, request -> NavigationResult.DENIED, (float) scale));
            render.icon.setVisibilitySurface(VisibilityGovernor.Surface.MARKER);
            render.icon.open();
        } else {
            render.icon.applyTransform(new MenuTransform(position, new Vector(), position.getYaw(),
                0F, 0F, (float) scale), (float) scale);
            render.icon.tick();
        }
    }

    private void applyBeam(Render render, MarkerCandidate candidate, Location anchor, boolean detailed) {
        MarkerSpec.Beam beam = candidate.spec().beam();
        if (!beam.enabled() || !detailed) {
            if (render.beam != null) {
                DisplayEntityManager.delete(render.beam, viewer);
                render.beam = null;
            }
            return;
        }
        if (render.beam != null) {
            render.beamGroup.refresh();
            return;
        }
        BlockData data = blockData(beam.material(), candidate.id());
        if (data == null) {
            return;
        }
        Location base = anchor.clone();
        base.setY(base.getY() - beam.height() / 2.0D);
        DisplayEntity display = DisplayEntity.Builder.blockDisplay(data, base);
        display.scale(new Vector3f((float) beam.width(), (float) beam.height(), (float) beam.width()));
        display.translation(new Vector3f((float) (-beam.width() / 2.0D), 0.0F, (float) (-beam.width() / 2.0D)));
        if (beam.glowColor() != null) {
            display.entityFlags((byte) (display.entityFlags() | 0x40));
            display.glowColorOverride(MarkerColors.parse(beam.glowColor(), "beam glowColor"));
        }
        render.beamGroup = DisplayEntityManager.group(viewer, VisibilityGovernor.Surface.MARKER);
        render.beam = DisplayEntityManager.add(render.beamGroup, display);
        DisplayEntityManager.spawn(render.beam, viewer);
    }

    private void applyEdge(Render render, MarkerCandidate candidate, EdgeIndicatorMath.Indicator indicator) {
        MarkerSpec.Edge edge = candidate.spec().edge();
        if (!edge.enabled() || indicator == null || indicator.inside() || edge.arrow().isBlank()) {
            if (render.edge != null) {
                render.edge.destroy();
                render.edge = null;
            }
            return;
        }
        if (render.edge == null) {
            render.edge = create("gloss-marker-edge:" + candidate.id(), edgePoint(indicator));
            render.edge.setRenderedLines(List.of(edge.arrow()));
        }
        render.edge.bindPosition(viewer, () -> edgePoint(latest(candidate)));
        render.edge.bindPresentation(viewer, () -> edgePresentation(latest(candidate).bearing()));
    }

    static HologramPresentation edgePresentation(EdgeIndicatorMath.Bearing bearing) {
        double rotation = switch (bearing) {
            case RIGHT -> 0.0D;
            case UP -> 90.0D;
            case LEFT -> 180.0D;
            case DOWN -> 270.0D;
        };
        return new HologramPresentation(1.0D, 1.0D, 1.0D, 0.0D, 0.0D, rotation, 1.0D);
    }

    private EdgeIndicatorMath.Indicator latest(MarkerCandidate candidate) {
        Location eye = viewer.getEyeLocation();
        return EdgeIndicatorMath.resolve(eye.getYaw(), eye.getPitch(),
            new Vector(candidate.x() - eye.getX(), candidate.y() - eye.getY(),
                candidate.z() - eye.getZ()), candidate.spec().edge().margin());
    }

    private Location edgePoint(EdgeIndicatorMath.Indicator indicator) {
        Location eye = viewer.getEyeLocation();
        return eye.clone().add(EdgeIndicatorMath.offset(eye.getYaw(), eye.getPitch(), indicator));
    }

    private TemporaryHologram create(String id, Location location) {
        TemporaryHologram hologram = plugin.holograms().createTemporary(id, location, FOREVER_MS);
        plugin.holograms().setVisibilitySurface(hologram, VisibilityGovernor.Surface.MARKER);
        hologram.viewers().whitelist();
        hologram.viewers().add(viewer.getUniqueId());
        return hologram;
    }

    private BlockData blockData(String material, String markerId) {
        try {
            return Bukkit.createBlockData(material);
        } catch (IllegalArgumentException failure) {
            Gloss.warnThrottled("marker-beam-material:" + markerId,
                "Marker %s declares beam material %s, which is not a block; the beam was skipped.",
                markerId, material);
            return null;
        }
    }

    public UUID viewerId() {
        return viewer.getUniqueId();
    }
}
