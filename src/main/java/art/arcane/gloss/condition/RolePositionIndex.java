package art.arcane.gloss.condition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Predicate;

public final class RolePositionIndex<T> {
    private static final int CELL_SIZE = 64;
    private final Map<UUID, Point<T>> points;
    private final Map<UUID, Map<Cell, List<Point<T>>>> worlds;

    public RolePositionIndex(Collection<Point<T>> source) {
        Map<UUID, Point<T>> indexed = new HashMap<>(source.size());
        Map<UUID, Map<Cell, List<Point<T>>>> grouped = new HashMap<>();
        for (Point<T> point : source) {
            indexed.put(point.id(), point);
            grouped.computeIfAbsent(point.world(), ignored -> new HashMap<>())
                .computeIfAbsent(new Cell(cell(point.x()), cell(point.y()), cell(point.z())), ignored -> new ArrayList<>())
                .add(point);
        }
        points = Map.copyOf(indexed);
        worlds = Map.copyOf(grouped);
    }

    public List<T> nearest(UUID viewer, double range, int limit, Predicate<T> visible) {
        Point<T> origin = points.get(viewer);
        if (origin == null || !Double.isFinite(range) || range < 0 || limit < 1) {
            return List.of();
        }
        Map<Cell, List<Point<T>>> buckets = worlds.get(origin.world());
        Comparator<Nearby<T>> order = Comparator.comparingDouble((Nearby<T> entry) -> entry.distance())
            .thenComparing(entry -> entry.point().id());
        PriorityQueue<Nearby<T>> selected = new PriorityQueue<>(Math.min(limit, points.size()), order.reversed());
        int minX = cell(origin.x() - range), maxX = cell(origin.x() + range);
        int minY = cell(origin.y() - range), maxY = cell(origin.y() + range);
        int minZ = cell(origin.z() - range), maxZ = cell(origin.z() + range);
        double cells = ((double) maxX - minX + 1) * ((double) maxY - minY + 1) * ((double) maxZ - minZ + 1);
        if (cells > buckets.size()) {
            for (Map.Entry<Cell, List<Point<T>>> bucket : buckets.entrySet()) {
                Cell key = bucket.getKey();
                if (key.x() >= minX && key.x() <= maxX && key.y() >= minY && key.y() <= maxY
                    && key.z() >= minZ && key.z() <= maxZ) {
                    select(bucket.getValue(), origin, range * range, limit, visible, selected, order);
                }
            }
        } else {
            for (long x = minX; x <= maxX; x++) {
                for (long y = minY; y <= maxY; y++) {
                    for (long z = minZ; z <= maxZ; z++) {
                        List<Point<T>> bucket = buckets.get(new Cell((int) x, (int) y, (int) z));
                        if (bucket != null) {
                            select(bucket, origin, range * range, limit, visible, selected, order);
                        }
                    }
                }
            }
        }
        List<Nearby<T>> ordered = new ArrayList<>(selected);
        ordered.sort(order);
        List<T> result = new ArrayList<>(ordered.size());
        for (Nearby<T> point : ordered) {
            result.add(point.point().value());
        }
        return List.copyOf(result);
    }

    private void select(List<Point<T>> bucket, Point<T> origin, double rangeSquared, int limit,
                        Predicate<T> visible, PriorityQueue<Nearby<T>> selected, Comparator<Nearby<T>> order) {
        for (Point<T> point : bucket) {
            double dx = point.x() - origin.x();
            double dy = point.y() - origin.y();
            double dz = point.z() - origin.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > rangeSquared || !visible.test(point.value())) {
                continue;
            }
            Nearby<T> nearby = new Nearby<>(point, distance);
            if (selected.size() < limit) {
                selected.add(nearby);
            } else if (order.compare(nearby, selected.peek()) < 0) {
                selected.poll();
                selected.add(nearby);
            }
        }
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    public record Point<T>(UUID id, UUID world, double x, double y, double z, T value) { }
    private record Cell(int x, int y, int z) { }
    private record Nearby<T>(Point<T> point, double distance) { }
}
