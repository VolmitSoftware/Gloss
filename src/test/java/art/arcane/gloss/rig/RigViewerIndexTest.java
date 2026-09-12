package art.arcane.gloss.rig;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigViewerIndexTest {
    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName" -> "world-" + id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "World";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void nearbyFindsViewersInRangeAcrossChunkBucketsAndRemoveForgetsThem() {
        World overworld = world(UUID.randomUUID());
        World nether = world(UUID.randomUUID());
        RigViewerIndex index = new RigViewerIndex();
        Player near = player(UUID.randomUUID());
        Player far = player(UUID.randomUUID());
        Player elsewhere = player(UUID.randomUUID());
        assertTrue(index.update(near, new Location(overworld, 20.0D, 64.0D, 20.0D)));
        assertTrue(index.update(far, new Location(overworld, 200.0D, 64.0D, 20.0D)));
        assertTrue(index.update(elsewhere, new Location(nether, 21.0D, 64.0D, 21.0D)));
        assertFalse(index.update(near, new Location(overworld, 20.0D, 64.0D, 20.0D)));

        List<RigViewerIndex.Viewer> found = index.nearby(new Location(overworld, 0.0D, 64.0D, 0.0D), 40.0D);
        assertEquals(1, found.size());
        assertEquals(near.getUniqueId(), found.get(0).id());
        assertTrue(index.anyNearby(new Location(overworld, 190.0D, 64.0D, 20.0D), 16.0D));
        assertFalse(index.anyNearby(new Location(overworld, 100.0D, 64.0D, 100.0D), 8.0D));
        assertEquals(3, index.size());

        index.remove(near.getUniqueId());
        assertTrue(index.nearby(new Location(overworld, 0.0D, 64.0D, 0.0D), 40.0D).isEmpty());
        assertEquals(2, index.size());
        index.clear();
        assertEquals(0, index.size());
    }

    @Test
    void movingBetweenChunksReportsATransition() {
        World overworld = world(UUID.randomUUID());
        RigViewerIndex index = new RigViewerIndex();
        Player mover = player(UUID.randomUUID());
        assertTrue(index.update(mover, new Location(overworld, 1.0D, 64.0D, 1.0D)));
        assertFalse(index.update(mover, new Location(overworld, 2.0D, 64.0D, 2.0D)));
        assertTrue(index.update(mover, new Location(overworld, 40.0D, 64.0D, 2.0D)));
        assertEquals(1, index.nearby(new Location(overworld, 40.0D, 64.0D, 2.0D), 1.0D).size());
    }
}
