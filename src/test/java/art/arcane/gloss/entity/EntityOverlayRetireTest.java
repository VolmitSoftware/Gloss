package art.arcane.gloss.entity;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source that holds per-pair state outside the pane - nameplate suppression holds a team that
 * hides the vanilla name tag - has to hear when a pair stops being drawn, or the subject's real
 * tag stays hidden for that viewer for the rest of their session.
 */
class EntityOverlayRetireTest {
    private static final String PLAYERS_BY_SOURCE = """
        {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0}
        """;

    @TempDir
    File dataFolder;

    @Test
    void aSubjectWhoLeavesRangeRetiresTheSourceClaimForThatPair() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle viewer = harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.PlayerHandle subject = harness.join("B", world, 2, 64, 0);
            EntityOverlayService service = harness.service(PLAYERS_BY_SOURCE);
            RecordingSource source = new RecordingSource();
            service.registerSource(source);
            harness.drive(service);

            subject.location = new Location(world.proxy, 4000, 64, 0);
            for (int pass = 0; pass < 4; pass++) {
                harness.drive(service);
            }

            assertTrue(source.retired.contains(pair(viewer.uuid, subject.uuid)),
                source.retired.toString());
        }
    }

    @Test
    void aViewerWhoQuitsRetiresTheSourceClaimForEverySubject() {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle viewer = harness.join("A", world, 0, 64, 0);
            EntityOverlayHarness.PlayerHandle subject = harness.join("B", world, 2, 64, 0);
            EntityOverlayService service = harness.service(PLAYERS_BY_SOURCE);
            RecordingSource source = new RecordingSource();
            service.registerSource(source);
            harness.drive(service);

            harness.quit(viewer);
            service.onQuit(new PlayerQuitEvent(viewer.proxy, (String) null));

            assertTrue(source.retired.contains(pair(viewer.uuid, subject.uuid)),
                source.retired.toString());
        }
    }

    private static String pair(UUID viewerId, UUID targetId) {
        return viewerId + "->" + targetId;
    }

    private static final class RecordingSource implements EntityOverlaySource {
        private final List<String> retired = new ArrayList<>();

        @Override
        public boolean wants(LivingEntity target) {
            return target instanceof Player;
        }

        @Override
        public Pane prepare(Player viewer, LivingEntity target, EntityOverlayText.Snapshot snapshot) {
            return null;
        }

        @Override
        public void retired(UUID viewerId, UUID targetId) {
            retired.add(pair(viewerId, targetId));
        }
    }
}
