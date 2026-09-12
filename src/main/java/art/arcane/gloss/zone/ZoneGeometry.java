package art.arcane.gloss.zone;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a zone's volume into the edges and faces a renderer draws. Everything here is pure so the
 * discretization is pinned by tests instead of by what a world happened to look like.
 */
public final class ZoneGeometry {
    /** Sides a cylinder is drawn with; fine enough to read as round at 64 blocks, cheap enough to sample. */
    public static final int CYLINDER_SEGMENTS = 32;

    public record Segment(Vector from, Vector to) {
        public Segment {
            from = from.clone();
            to = to.clone();
        }

        @Override
        public Vector from() {
            return from.clone();
        }

        @Override
        public Vector to() {
            return to.clone();
        }

        public double length() {
            return from.distance(to);
        }
    }

    /** A flat quad: its centre, outward normal, in-plane axes, and extents along them. */
    public record Face(Vector centre, Vector normal, Vector right, Vector up, double width, double height) {
        public Face {
            centre = centre.clone();
            normal = normal.clone().normalize();
            right = right.clone().normalize();
            up = up.clone().normalize();
        }

        @Override
        public Vector centre() {
            return centre.clone();
        }

        @Override
        public Vector normal() {
            return normal.clone();
        }

        @Override
        public Vector right() {
            return right.clone();
        }

        @Override
        public Vector up() {
            return up.clone();
        }
    }

    private ZoneGeometry() {
    }

    public static List<Segment> edges(ZoneShape shape) {
        return switch (shape.type()) {
            case "cuboid" -> cuboidEdges(shape);
            case "cylinder" -> cylinderEdges(shape);
            case "polygon" -> polygonEdges(shape);
            default -> List.of();
        };
    }

    public static List<Face> faces(ZoneShape shape) {
        return switch (shape.type()) {
            case "cuboid" -> cuboidFaces(shape);
            case "cylinder" -> cylinderFaces(shape);
            case "polygon" -> polygonFaces(shape);
            default -> List.of();
        };
    }

    /** True while the eye is on the outward side of the face's plane. */
    public static boolean facing(Face face, Vector eye) {
        return face.normal().dot(eye.clone().subtract(face.centre())) > 0.0D;
    }

    public static List<Vector> sample(Segment segment, double spacing) {
        double length = segment.length();
        int steps = length <= 0.0D ? 0 : Math.max(1, (int) Math.ceil(length / Math.max(0.01D, spacing)));
        List<Vector> points = new ArrayList<>(steps + 1);
        Vector from = segment.from();
        Vector delta = segment.to().subtract(from);
        for (int index = 0; index <= steps; index++) {
            points.add(from.clone().add(delta.clone().multiply(steps == 0 ? 0.0D : (double) index / steps)));
        }
        return List.copyOf(points);
    }

    public static Vector centre(ZoneShape shape) {
        return switch (shape.type()) {
            case "cuboid" -> new Vector((shape.min()[0] + shape.max()[0]) / 2.0D,
                (shape.min()[1] + shape.max()[1]) / 2.0D, (shape.min()[2] + shape.max()[2]) / 2.0D);
            case "cylinder" -> new Vector(shape.center()[0],
                (shape.minY() + shape.maxY()) / 2.0D, shape.center()[1]);
            case "polygon" -> polygonCentre(shape);
            default -> new Vector();
        };
    }

    public static boolean contains(ZoneShape shape, Vector point) {
        return switch (shape.type()) {
            case "cuboid" -> within(point.getX(), shape.min()[0], shape.max()[0])
                && within(point.getY(), shape.min()[1], shape.max()[1])
                && within(point.getZ(), shape.min()[2], shape.max()[2]);
            case "cylinder" -> within(point.getY(), shape.minY(), shape.maxY())
                && Math.hypot(point.getX() - shape.center()[0], point.getZ() - shape.center()[1]) <= shape.radius();
            case "polygon" -> within(point.getY(), shape.minY(), shape.maxY())
                && insidePolygon(shape.points(), point.getX(), point.getZ());
            default -> false;
        };
    }

    private static boolean within(double value, double low, double high) {
        return value >= low && value <= high;
    }

    private static boolean insidePolygon(List<double[]> points, double x, double z) {
        boolean inside = false;
        for (int index = 0, previous = points.size() - 1; index < points.size(); previous = index++) {
            double[] a = points.get(index);
            double[] b = points.get(previous);
            boolean straddles = (a[1] > z) != (b[1] > z);
            if (straddles && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static List<Segment> cuboidEdges(ZoneShape shape) {
        double[] min = shape.min();
        double[] max = shape.max();
        Vector[] corners = new Vector[]{
            new Vector(min[0], min[1], min[2]), new Vector(max[0], min[1], min[2]),
            new Vector(max[0], min[1], max[2]), new Vector(min[0], min[1], max[2]),
            new Vector(min[0], max[1], min[2]), new Vector(max[0], max[1], min[2]),
            new Vector(max[0], max[1], max[2]), new Vector(min[0], max[1], max[2])
        };
        int[][] pairs = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        List<Segment> edges = new ArrayList<>(pairs.length);
        for (int[] pair : pairs) {
            edges.add(new Segment(corners[pair[0]], corners[pair[1]]));
        }
        return List.copyOf(edges);
    }

    private static List<Segment> cylinderEdges(ZoneShape shape) {
        List<Vector> low = ring(shape, shape.minY());
        List<Vector> high = ring(shape, shape.maxY());
        List<Segment> edges = new ArrayList<>(CYLINDER_SEGMENTS * 2 + 4);
        for (int index = 0; index < CYLINDER_SEGMENTS; index++) {
            int next = (index + 1) % CYLINDER_SEGMENTS;
            edges.add(new Segment(low.get(index), low.get(next)));
            edges.add(new Segment(high.get(index), high.get(next)));
        }
        int quarter = CYLINDER_SEGMENTS / 4;
        for (int corner = 0; corner < 4; corner++) {
            int index = corner * quarter;
            edges.add(new Segment(low.get(index), high.get(index)));
        }
        return List.copyOf(edges);
    }

    private static List<Segment> polygonEdges(ZoneShape shape) {
        List<double[]> points = shape.points();
        List<Segment> edges = new ArrayList<>(points.size() * 3);
        for (int index = 0; index < points.size(); index++) {
            double[] a = points.get(index);
            double[] b = points.get((index + 1) % points.size());
            edges.add(new Segment(new Vector(a[0], shape.minY(), a[1]), new Vector(b[0], shape.minY(), b[1])));
            edges.add(new Segment(new Vector(a[0], shape.maxY(), a[1]), new Vector(b[0], shape.maxY(), b[1])));
            edges.add(new Segment(new Vector(a[0], shape.minY(), a[1]), new Vector(a[0], shape.maxY(), a[1])));
        }
        return List.copyOf(edges);
    }

    private static List<Vector> ring(ZoneShape shape, double y) {
        List<Vector> points = new ArrayList<>(CYLINDER_SEGMENTS);
        for (int index = 0; index < CYLINDER_SEGMENTS; index++) {
            double angle = 2.0D * Math.PI * index / CYLINDER_SEGMENTS;
            points.add(new Vector(shape.center()[0] + Math.cos(angle) * shape.radius(), y,
                shape.center()[1] + Math.sin(angle) * shape.radius()));
        }
        return points;
    }

    private static List<Face> cuboidFaces(ZoneShape shape) {
        double[] min = shape.min();
        double[] max = shape.max();
        double width = max[0] - min[0];
        double height = max[1] - min[1];
        double depth = max[2] - min[2];
        Vector centre = centre(shape);
        List<Face> faces = new ArrayList<>(6);
        faces.add(new Face(new Vector(centre.getX(), centre.getY(), min[2]), new Vector(0, 0, -1),
            new Vector(1, 0, 0), new Vector(0, 1, 0), width, height));
        faces.add(new Face(new Vector(centre.getX(), centre.getY(), max[2]), new Vector(0, 0, 1),
            new Vector(1, 0, 0), new Vector(0, 1, 0), width, height));
        faces.add(new Face(new Vector(min[0], centre.getY(), centre.getZ()), new Vector(-1, 0, 0),
            new Vector(0, 0, 1), new Vector(0, 1, 0), depth, height));
        faces.add(new Face(new Vector(max[0], centre.getY(), centre.getZ()), new Vector(1, 0, 0),
            new Vector(0, 0, 1), new Vector(0, 1, 0), depth, height));
        faces.add(new Face(new Vector(centre.getX(), min[1], centre.getZ()), new Vector(0, -1, 0),
            new Vector(1, 0, 0), new Vector(0, 0, 1), width, depth));
        faces.add(new Face(new Vector(centre.getX(), max[1], centre.getZ()), new Vector(0, 1, 0),
            new Vector(1, 0, 0), new Vector(0, 0, 1), width, depth));
        return List.copyOf(faces);
    }

    private static List<Face> cylinderFaces(ZoneShape shape) {
        List<Face> faces = new ArrayList<>(CYLINDER_SEGMENTS);
        double height = shape.maxY() - shape.minY();
        double midY = (shape.minY() + shape.maxY()) / 2.0D;
        double chord = 2.0D * shape.radius() * Math.sin(Math.PI / CYLINDER_SEGMENTS);
        for (int index = 0; index < CYLINDER_SEGMENTS; index++) {
            double angle = 2.0D * Math.PI * (index + 0.5D) / CYLINDER_SEGMENTS;
            Vector normal = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Vector centre = new Vector(shape.center()[0] + normal.getX() * shape.radius(), midY,
                shape.center()[1] + normal.getZ() * shape.radius());
            faces.add(new Face(centre, normal, normal.clone().crossProduct(new Vector(0, 1, 0)),
                new Vector(0, 1, 0), chord, height));
        }
        return List.copyOf(faces);
    }

    private static List<Face> polygonFaces(ZoneShape shape) {
        List<double[]> points = shape.points();
        double height = shape.maxY() - shape.minY();
        double midY = (shape.minY() + shape.maxY()) / 2.0D;
        List<Face> faces = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            double[] a = points.get(index);
            double[] b = points.get((index + 1) % points.size());
            Vector along = new Vector(b[0] - a[0], 0, b[1] - a[1]);
            double width = along.length();
            if (width <= 0.0D) {
                continue;
            }
            Vector normal = new Vector(along.getZ(), 0, -along.getX()).normalize();
            faces.add(new Face(new Vector((a[0] + b[0]) / 2.0D, midY, (a[1] + b[1]) / 2.0D), normal,
                along.clone().normalize(), new Vector(0, 1, 0), width, height));
        }
        return List.copyOf(faces);
    }

    private static Vector polygonCentre(ZoneShape shape) {
        double x = 0.0D;
        double z = 0.0D;
        for (double[] point : shape.points()) {
            x += point[0];
            z += point[1];
        }
        int count = shape.points().size();
        return new Vector(x / count, (shape.minY() + shape.maxY()) / 2.0D, z / count);
    }
}
