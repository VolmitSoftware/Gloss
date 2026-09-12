package art.arcane.gloss.waypoint;

import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.api.WaypointSpec;
import art.arcane.gloss.api.Waypoints;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class WaypointsApiTest {
    private static final UUID VIEWER = UUID.randomUUID();

    @AfterEach
    void clear() {
        Waypoints.clear();
    }

    @Test
    void tracksASpecForOneViewerOnly() {
        Waypoints.track(plugin("Quests"), player(VIEWER), spec("quest", 0xFF0000));

        Assertions.assertEquals(List.of("quest"),
            Waypoints.tracked(VIEWER).stream().map(WaypointSpec::id).toList());
        Assertions.assertEquals(List.of(), Waypoints.tracked(UUID.randomUUID()));
    }

    @Test
    void trackingTheSameIdTwiceReplacesIt() {
        Plugin owner = plugin("Quests");
        Waypoints.track(owner, player(VIEWER), spec("quest", 0xFF0000));
        Waypoints.track(owner, player(VIEWER), spec("quest", 0x00FF00));

        Assertions.assertEquals(1, Waypoints.tracked(VIEWER).size());
        Assertions.assertEquals(0x00FF00, Waypoints.tracked(VIEWER).getFirst().color());
    }

    @Test
    void untrackDropsOnlyThatId() {
        Plugin owner = plugin("Quests");
        Waypoints.track(owner, player(VIEWER), spec("quest", 0xFF0000));
        Waypoints.track(owner, player(VIEWER), spec("shop", 0x0000FF));

        Waypoints.untrack(owner, player(VIEWER), "quest");

        Assertions.assertEquals(List.of("shop"),
            Waypoints.tracked(VIEWER).stream().map(WaypointSpec::id).toList());
    }

    @Test
    void untrackFromAnotherPluginIsIgnored() {
        Waypoints.track(plugin("Quests"), player(VIEWER), spec("quest", 0xFF0000));

        Waypoints.untrack(plugin("Other"), player(VIEWER), "quest");

        Assertions.assertEquals(1, Waypoints.tracked(VIEWER).size());
    }

    @Test
    void forgetDropsEverythingForAViewer() {
        Waypoints.track(plugin("Quests"), player(VIEWER), spec("quest", 0xFF0000));

        Waypoints.forget(VIEWER);

        Assertions.assertEquals(List.of(), Waypoints.tracked(VIEWER));
    }

    private static WaypointSpec spec(String id, int color) {
        return new WaypointSpec(id, MarkerAnchor.position("world", 1, 2, 3), color, "default", 0);
    }

    private static Player player(UUID id) {
        return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "viewer";
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }

    private static Plugin plugin(String name) {
        return (Plugin) CharacterizationSupport.proxy(new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> true;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
