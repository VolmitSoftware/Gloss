package art.arcane.gloss.behavior;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTrackerTest {
    private final World world = BehaviorTestSupport.world("survival");
    private final Map<Double, Set<String>> regionsByX = new HashMap<>();
    private final List<String> fired = new ArrayList<>();
    private final RegionTracker tracker = new RegionTracker(
        location -> regionsByX.getOrDefault(location.getX(), Set.of()),
        (trigger, event) -> fired.add(trigger.key() + ":" + event.selector() + ":" + event.viewer().getName()));

    @Test
    void firstPollEntersTheRegionsThePlayerAlreadyStandsIn() {
        regionsByX.put(1.0D, Set.of("spawn"));
        Player alice = BehaviorTestSupport.player("alice", Set.of(), world, 1.0D);

        tracker.tick(alice);
        tracker.tick(alice);

        assertEquals(List.of("region_enter:spawn:alice"), fired);
    }

    @Test
    void movingBetweenRegionsFiresLeaveThenEnterOnlyForTheDifference() {
        regionsByX.put(1.0D, Set.of("spawn", "market"));
        regionsByX.put(2.0D, Set.of("market", "arena"));
        MovingPlayer moving = new MovingPlayer("bob", 1.0D);

        tracker.tick(moving.player());
        fired.clear();
        moving.x = 2.0D;
        tracker.tick(moving.player());

        assertEquals(List.of("region_leave:spawn:bob", "region_enter:arena:bob"), fired);
        moving.x = 3.0D;
        tracker.tick(moving.player());
        assertTrue(fired.containsAll(List.of("region_leave:market:bob", "region_leave:arena:bob")));
        assertEquals(4, fired.size());
    }

    @Test
    void forgettingAPlayerRestartsFromNothing() {
        regionsByX.put(1.0D, Set.of("spawn"));
        Player alice = BehaviorTestSupport.player("alice", Set.of(), world, 1.0D);

        tracker.tick(alice);
        tracker.forget(alice.getUniqueId());
        tracker.tick(alice);

        assertEquals(List.of("region_enter:spawn:alice", "region_enter:spawn:alice"), fired);
    }

    private final class MovingPlayer {
        private final String name;
        private double x;

        private MovingPlayer(String name, double x) {
            this.name = name;
            this.x = x;
        }

        private Player player() {
            Player base = BehaviorTestSupport.player(name, Set.of(), world, x);
            return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> method.getName().equals("getLocation")
                    ? new Location(world, x, 64.0D, 0.0D) : method.invoke(base, args));
        }
    }
}
