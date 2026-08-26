package art.arcane.gloss.particle;

import art.arcane.gloss.api.ParticleLayer;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public final class ParticleGeometrySampler {
    private ParticleGeometrySampler() {
    }

    public static List<Vector> sample(ParticleLayer.Geometry geometry, List<ParticleRect> targets, int maximum) {
        int limit = Math.max(1, maximum);
        List<Vector> output = new ArrayList<>(Math.min(limit, 64));
        if (geometry.type().equals("line")) {
            addLine(output, geometry.from(), geometry.to(), geometry.spacing(), limit, true);
            return List.copyOf(output);
        }
        if (geometry.type().equals("polyline")) {
            List<Vector> points = geometry.points();
            for (int index = 1; index < points.size() && output.size() < limit; index++) {
                addLine(output, points.get(index - 1), points.get(index), geometry.spacing(), limit, index == 1);
            }
            return List.copyOf(output);
        }
        List<ParticleRect> activeTargets = targets == null || targets.isEmpty()
            ? List.of(explicitRect(geometry))
            : targets;
        for (ParticleRect target : activeTargets) {
            if (output.size() >= limit) {
                break;
            }
            sampleTarget(output, geometry, target, limit);
        }
        return List.copyOf(output);
    }

    private static ParticleRect explicitRect(ParticleLayer.Geometry geometry) {
        return new ParticleRect(0.0D, 0.0D, 0.0D,
            geometry.width() == null ? 0.0D : geometry.width(),
            geometry.height() == null ? 0.0D : geometry.height(),
            geometry.depth() == null ? 0.0D : geometry.depth());
    }

    private static void sampleTarget(List<Vector> output, ParticleLayer.Geometry geometry,
                                     ParticleRect target, int limit) {
        double padding = geometry.padding();
        ParticleRect expanded = new ParticleRect(target.centerX(), target.centerY(), target.centerZ(),
            target.width() + padding * 2.0D,
            target.height() + padding * 2.0D,
            target.depth() + padding * 2.0D);
        switch (geometry.type()) {
            case "point" -> add(output, new Vector(expanded.centerX(), expanded.centerY(), expanded.centerZ()), limit);
            case "filledPlane", "glyphFill" -> addPlane(output, expanded, geometry.spacing(), limit);
            case "cuboid" -> addCuboid(output, expanded, geometry.spacing(), limit);
            case "letterBounds", "glyphOutline", "outline" -> addOutline(output, expanded, geometry.spacing(), limit);
            default -> throw new IllegalArgumentException("Unsupported particle geometry: " + geometry.type());
        }
    }

    private static void addPlane(List<Vector> output, ParticleRect target, double spacing, int limit) {
        double left = target.centerX() - target.width() / 2.0D;
        double bottom = target.centerY() - target.height() / 2.0D;
        int columns = segments(target.width(), spacing);
        int rows = segments(target.height(), spacing);
        for (int row = 0; row <= rows && output.size() < limit; row++) {
            double y = bottom + target.height() * row / rows;
            for (int column = 0; column <= columns && output.size() < limit; column++) {
                double x = left + target.width() * column / columns;
                add(output, new Vector(x, y, target.centerZ()), limit);
            }
        }
    }

    private static void addOutline(List<Vector> output, ParticleRect target, double spacing, int limit) {
        double halfWidth = target.width() / 2.0D;
        double halfHeight = target.height() / 2.0D;
        Vector bottomLeft = new Vector(target.centerX() - halfWidth, target.centerY() - halfHeight, target.centerZ());
        Vector bottomRight = new Vector(target.centerX() + halfWidth, target.centerY() - halfHeight, target.centerZ());
        Vector topRight = new Vector(target.centerX() + halfWidth, target.centerY() + halfHeight, target.centerZ());
        Vector topLeft = new Vector(target.centerX() - halfWidth, target.centerY() + halfHeight, target.centerZ());
        addLine(output, bottomLeft, bottomRight, spacing, limit, true);
        addLine(output, bottomRight, topRight, spacing, limit, false);
        addLine(output, topRight, topLeft, spacing, limit, false);
        addLine(output, topLeft, bottomLeft, spacing, limit, false);
    }

    private static void addCuboid(List<Vector> output, ParticleRect target, double spacing, int limit) {
        double halfWidth = target.width() / 2.0D;
        double halfHeight = target.height() / 2.0D;
        double halfDepth = target.depth() / 2.0D;
        Vector[] corners = new Vector[] {
            new Vector(target.centerX() - halfWidth, target.centerY() - halfHeight, target.centerZ() - halfDepth),
            new Vector(target.centerX() + halfWidth, target.centerY() - halfHeight, target.centerZ() - halfDepth),
            new Vector(target.centerX() + halfWidth, target.centerY() + halfHeight, target.centerZ() - halfDepth),
            new Vector(target.centerX() - halfWidth, target.centerY() + halfHeight, target.centerZ() - halfDepth),
            new Vector(target.centerX() - halfWidth, target.centerY() - halfHeight, target.centerZ() + halfDepth),
            new Vector(target.centerX() + halfWidth, target.centerY() - halfHeight, target.centerZ() + halfDepth),
            new Vector(target.centerX() + halfWidth, target.centerY() + halfHeight, target.centerZ() + halfDepth),
            new Vector(target.centerX() - halfWidth, target.centerY() + halfHeight, target.centerZ() + halfDepth)
        };
        int[][] edges = new int[][] {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int index = 0; index < edges.length && output.size() < limit; index++) {
            int[] edge = edges[index];
            addLine(output, corners[edge[0]], corners[edge[1]], spacing, limit, index == 0);
        }
    }

    private static void addLine(List<Vector> output, Vector from, Vector to, double spacing,
                                int limit, boolean includeStart) {
        Vector delta = to.clone().subtract(from);
        double length = delta.length();
        int segments = segments(length, spacing);
        int start = includeStart ? 0 : 1;
        for (int index = start; index <= segments && output.size() < limit; index++) {
            double progress = (double) index / segments;
            add(output, from.clone().add(delta.clone().multiply(progress)), limit);
        }
    }

    private static int segments(double length, double spacing) {
        return Math.max(1, (int) Math.ceil(length / spacing));
    }

    private static void add(List<Vector> output, Vector point, int limit) {
        if (output.size() < limit) {
            output.add(point);
        }
    }
}
