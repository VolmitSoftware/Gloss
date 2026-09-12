package art.arcane.gloss.leaderboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardSnapshotIoTest {
    private static final UUID ALPHA = UUID.nameUUIDFromBytes("alpha".getBytes());

    @TempDir
    Path dataFolder;

    @Test
    void aSavedSnapshotReadsBackIdentically() throws IOException {
        LeaderboardSnapshotStore store = new LeaderboardSnapshotStore(dataFolder.toFile());
        LeaderboardSnapshot snapshot = LeaderboardSnapshot.empty("playtime")
            .withPeriodStart(1_700_000_000_000L)
            .sampled(ALPHA, "Alpha", 42.0D, 1_700_000_100_000L);

        store.save(snapshot);

        assertEquals(snapshot, store.load("playtime"));
    }

    @Test
    void anAbsentSnapshotLoadsAsEmpty() {
        LeaderboardSnapshotStore store = new LeaderboardSnapshotStore(dataFolder.toFile());

        LeaderboardSnapshot loaded = store.load("kills");

        assertEquals("kills", loaded.id());
        assertTrue(loaded.entries().isEmpty());
    }

    @Test
    void aCorruptSnapshotIsSetAsideAndTheBoardStartsFresh() throws IOException {
        LeaderboardSnapshotStore store = new LeaderboardSnapshotStore(dataFolder.toFile());
        Path file = dataFolder.resolve("leaderboards/state/kills.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");

        LeaderboardSnapshot loaded = store.load("kills");

        assertTrue(loaded.entries().isEmpty());
        assertTrue(Files.notExists(file));
        try (var listing = Files.list(file.getParent())) {
            List<String> kept = listing.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("kills.json.corrupt-")).toList();
            assertEquals(1, kept.size());
        }
    }

    @Test
    void savingTwiceLeavesOneFileAndTheLatestContent() throws IOException {
        LeaderboardSnapshotStore store = new LeaderboardSnapshotStore(dataFolder.toFile());
        store.save(LeaderboardSnapshot.empty("playtime").sampled(ALPHA, "Alpha", 1.0D, 1L));
        store.save(LeaderboardSnapshot.empty("playtime").sampled(ALPHA, "Alpha", 2.0D, 2L));

        assertEquals(2.0D, store.load("playtime").entry(ALPHA).allTime());
        try (var listing = Files.list(dataFolder.resolve("leaderboards/state"))) {
            assertEquals(1, listing.count());
        }
    }
}
