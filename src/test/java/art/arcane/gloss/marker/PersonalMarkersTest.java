package art.arcane.gloss.marker;

import art.arcane.gloss.state.PlayerSections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

class PersonalMarkersTest {
    @TempDir
    Path dataFolder;

    @Test
    void storesAndListsAMarker() {
        PersonalMarkers markers = new PersonalMarkers(new PlayerSections(dataFolder));
        UUID player = UUID.randomUUID();

        markers.set(player, "Home", "world", 10.5D, 64.0D, -3.25D);

        List<MarkerSpec> stored = markers.list(player);
        Assertions.assertEquals(1, stored.size());
        Assertions.assertEquals("home", stored.getFirst().id());
        Assertions.assertEquals("Home", stored.getFirst().label());
        Assertions.assertEquals(10.5D, stored.getFirst().anchor().x());
        Assertions.assertTrue(stored.getFirst().waypoint());
    }

    @Test
    void survivesAStoreRestart() {
        UUID player = UUID.randomUUID();
        new PersonalMarkers(new PlayerSections(dataFolder)).set(player, "Mine", "world", 1, 2, 3);

        List<MarkerSpec> stored = new PersonalMarkers(new PlayerSections(dataFolder)).list(player);

        Assertions.assertEquals(List.of("mine"), stored.stream().map(MarkerSpec::id).toList());
    }

    @Test
    void removeDropsOnlyThatMarker() {
        PersonalMarkers markers = new PersonalMarkers(new PlayerSections(dataFolder));
        UUID player = UUID.randomUUID();
        markers.set(player, "Home", "world", 1, 2, 3);
        markers.set(player, "Mine", "world", 4, 5, 6);

        Assertions.assertTrue(markers.remove(player, "Home"));

        Assertions.assertEquals(List.of("mine"), markers.list(player).stream().map(MarkerSpec::id).toList());
    }

    @Test
    void removingAnUnknownMarkerReportsFalse() {
        PersonalMarkers markers = new PersonalMarkers(new PlayerSections(dataFolder));

        Assertions.assertFalse(markers.remove(UUID.randomUUID(), "nowhere"));
    }

    @Test
    void namesAreCaseInsensitiveAndKeepTheirAuthoredLabel() {
        PersonalMarkers markers = new PersonalMarkers(new PlayerSections(dataFolder));
        UUID player = UUID.randomUUID();
        markers.set(player, "Home", "world", 1, 2, 3);

        markers.set(player, "HOME", "nether", 7, 8, 9);

        List<MarkerSpec> stored = markers.list(player);
        Assertions.assertEquals(1, stored.size());
        Assertions.assertEquals("HOME", stored.getFirst().label());
        Assertions.assertEquals("nether", stored.getFirst().anchor().world());
    }

    @Test
    void refusesMoreThanTheCap() {
        PersonalMarkers markers = new PersonalMarkers(new PlayerSections(dataFolder));
        UUID player = UUID.randomUUID();
        for (int index = 0; index < PersonalMarkers.MAX_PER_PLAYER; index++) {
            Assertions.assertTrue(markers.set(player, "m" + index, "world", index, 0, 0));
        }

        Assertions.assertFalse(markers.set(player, "overflow", "world", 0, 0, 0));
        Assertions.assertEquals(PersonalMarkers.MAX_PER_PLAYER, markers.list(player).size());
    }
}
