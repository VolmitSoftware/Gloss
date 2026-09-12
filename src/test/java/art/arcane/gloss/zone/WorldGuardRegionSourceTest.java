package art.arcane.gloss.zone;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class WorldGuardRegionSourceTest {
    public record FakeVector3(int x, int y, int z) {
        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }
    }

    public record FakeVector2(int x, int z) {
        public int getX() {
            return x;
        }

        public int getZ() {
            return z;
        }
    }

    public record FakeRegion(FakeVector3 min, FakeVector3 max, List<FakeVector2> points) {
        public FakeVector3 getMinimumPoint() {
            return min;
        }

        public FakeVector3 getMaximumPoint() {
            return max;
        }

        public List<FakeVector2> getPoints() {
            return points;
        }
    }

    public record FakeManager(FakeRegion region) {
        public FakeRegion getRegion(String id) {
            return id.equals("spawn") ? region : null;
        }
    }

    public record FakeContainer(FakeManager manager) {
        public FakeManager get(Object world) {
            return manager;
        }
    }

    @Test
    void readsACuboidRegionAsACuboidShape() {
        ZoneShape shape = WorldGuardRegionSource.read(cuboidContainer(), new Object(), "world", "spawn");

        Assertions.assertNotNull(shape);
        Assertions.assertEquals("cuboid", shape.type());
        Assertions.assertArrayEquals(new double[]{10, 60, 10}, shape.min());
        Assertions.assertArrayEquals(new double[]{21, 81, 21}, shape.max());
    }

    @Test
    void readsAPolygonalRegionAsAPolygonShape() {
        FakeContainer container = new FakeContainer(new FakeManager(new FakeRegion(
            new FakeVector3(0, 60, 0), new FakeVector3(20, 80, 20),
            List.of(new FakeVector2(0, 0), new FakeVector2(20, 0), new FakeVector2(10, 20)))));

        ZoneShape shape = WorldGuardRegionSource.read(container, new Object(), "world", "spawn");

        Assertions.assertEquals("polygon", shape.type());
        Assertions.assertEquals(3, shape.points().size());
        Assertions.assertEquals(60.0D, shape.minY());
        Assertions.assertEquals(81.0D, shape.maxY());
    }

    @Test
    void anUnknownRegionResolvesToNothing() {
        Assertions.assertNull(WorldGuardRegionSource.read(cuboidContainer(), new Object(), "world", "nowhere"));
    }

    @Test
    void aContainerWithoutTheExpectedShapeResolvesToNothing() {
        Assertions.assertNull(WorldGuardRegionSource.read("not a container", new Object(), "world", "spawn"));
    }

    private static FakeContainer cuboidContainer() {
        return new FakeContainer(new FakeManager(new FakeRegion(
            new FakeVector3(10, 60, 10), new FakeVector3(20, 80, 20),
            List.of(new FakeVector2(10, 10), new FakeVector2(20, 10),
                new FakeVector2(20, 20), new FakeVector2(10, 20)))));
    }
}
