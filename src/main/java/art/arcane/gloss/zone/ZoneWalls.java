package art.arcane.gloss.zone;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a zone's faces into block-display panels. A display's scale is a float vector, so one
 * 200-block quad would be a single huge entity the client culls badly; panels keep every display
 * inside a size the renderer can cull per viewer, and the per-zone budget caps how many a single
 * zone may ever spend.
 */
public final class ZoneWalls {
    /** Widest panel, in blocks; beyond this a display's culling box stops tracking the viewer well. */
    public static final double MAX_PANEL_WIDTH = 32.0D;
    /** Displays one zone may spend on one viewer. */
    public static final int MAX_PANELS_PER_ZONE = 24;
    /** Thickness of a wall panel; thin enough to read as a plane, thick enough not to z-fight. */
    public static final double PANEL_THICKNESS = 0.02D;

    public record Panel(Vector centre, Vector normal, double width, double height) {
        public Panel {
            centre = centre.clone();
            normal = normal.clone();
        }

        @Override
        public Vector centre() {
            return centre.clone();
        }

        @Override
        public Vector normal() {
            return normal.clone();
        }
    }

    private ZoneWalls() {
    }

    /** Every panel a zone would draw, truncated to {@link #MAX_PANELS_PER_ZONE}. */
    public static List<Panel> panels(ZoneShape shape) {
        List<Panel> panels = new ArrayList<>();
        for (ZoneGeometry.Face face : ZoneGeometry.faces(shape)) {
            for (Panel panel : split(face)) {
                if (panels.size() >= MAX_PANELS_PER_ZONE) {
                    return List.copyOf(panels);
                }
                panels.add(panel);
            }
        }
        return List.copyOf(panels);
    }

    public static List<Panel> split(ZoneGeometry.Face face) {
        int columns = Math.max(1, (int) Math.ceil(face.width() / MAX_PANEL_WIDTH));
        double width = face.width() / columns;
        List<Panel> panels = new ArrayList<>(columns);
        Vector right = face.right();
        Vector centre = face.centre();
        for (int column = 0; column < columns; column++) {
            double offset = -face.width() / 2.0D + width * (column + 0.5D);
            panels.add(new Panel(centre.clone().add(right.clone().multiply(offset)), face.normal(),
                width, face.height()));
        }
        return List.copyOf(panels);
    }
}
