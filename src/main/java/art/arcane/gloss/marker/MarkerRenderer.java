package art.arcane.gloss.marker;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.PacketUtils;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

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
        private DisplayEntity beam;
        private String labelText = "";
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
            if (beam != null) {
                PacketUtils.send(viewer, beam.remove());
                beam = null;
            }
        }

        int entityCount() {
            return (label == null ? 0 : 1) + (edge == null ? 0 : 1) + (beam == null ? 0 : 1);
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
        applyLabel(render, candidate, anchor, labelText, scale);
        applyBeam(render, candidate, anchor);
        applyEdge(render, candidate, indicator);
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

    private void applyBeam(Render render, MarkerCandidate candidate, Location anchor) {
        MarkerSpec.Beam beam = candidate.spec().beam();
        if (!beam.enabled()) {
            if (render.beam != null) {
                PacketUtils.send(viewer, render.beam.remove());
                render.beam = null;
            }
            return;
        }
        if (render.beam != null) {
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
        List<PacketWrapper<?>> packets = new ArrayList<>(display.spawn());
        PacketUtils.send(viewer, packets);
        render.beam = display;
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
            render.edge.bindPosition(viewer, () -> edgePoint(latest(candidate)));
        }
    }

    private EdgeIndicatorMath.Indicator latest(MarkerCandidate candidate) {
        Location eye = viewer.getEyeLocation();
        return EdgeIndicatorMath.resolve(eye.getYaw(), eye.getPitch(),
            new org.bukkit.util.Vector(candidate.x() - eye.getX(), candidate.y() - eye.getY(),
                candidate.z() - eye.getZ()), candidate.spec().edge().margin());
    }

    private Location edgePoint(EdgeIndicatorMath.Indicator indicator) {
        Location eye = viewer.getEyeLocation();
        return eye.clone().add(EdgeIndicatorMath.offset(eye.getYaw(), eye.getPitch(), indicator));
    }

    private TemporaryHologram create(String id, Location location) {
        TemporaryHologram hologram = plugin.holograms().createTemporary(id, location, FOREVER_MS);
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
