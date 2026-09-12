package art.arcane.gloss.camera;

import art.arcane.gloss.state.PlayerSections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

class CameraJournalTest {
    @TempDir
    Path dataFolder;

    @Test
    void anUnjournalledPlayerHasNothingToRestore() {
        Assertions.assertEquals(Optional.empty(),
            new CameraJournal(new PlayerSections(dataFolder)).read(UUID.randomUUID()));
    }

    @Test
    void aJournalledRideSurvivesARestart() {
        UUID player = UUID.randomUUID();
        new CameraJournal(new PlayerSections(dataFolder))
            .write(player, "world", 1.5D, 64.0D, -2.5D, 90.0F, 10.0F, "SURVIVAL");

        CameraJournal.Entry entry = new CameraJournal(new PlayerSections(dataFolder))
            .read(player).orElseThrow();

        Assertions.assertEquals("world", entry.world());
        Assertions.assertEquals(1.5D, entry.x());
        Assertions.assertEquals(-2.5D, entry.z());
        Assertions.assertEquals(90.0F, entry.yaw());
        Assertions.assertEquals("SURVIVAL", entry.gameMode());
    }

    @Test
    void clearingTheJournalLeavesNothingBehind() {
        UUID player = UUID.randomUUID();
        CameraJournal journal = new CameraJournal(new PlayerSections(dataFolder));
        journal.write(player, "world", 0, 64, 0, 0, 0, "CREATIVE");

        journal.clear(player);

        Assertions.assertEquals(Optional.empty(), journal.read(player));
    }

    @Test
    void aJournalMissingItsWorldIsIgnored() {
        UUID player = UUID.randomUUID();
        PlayerSections sections = new PlayerSections(dataFolder);
        sections.write(player, CameraJournal.SECTION, java.util.Map.of("x", 1.0D));

        Assertions.assertEquals(Optional.empty(), new CameraJournal(sections).read(player));
    }
}
