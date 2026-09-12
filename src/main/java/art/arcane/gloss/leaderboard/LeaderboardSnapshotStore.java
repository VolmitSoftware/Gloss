package art.arcane.gloss.leaderboard;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DocumentParsers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persisted leaderboard state under {@code leaderboards/state/}. A snapshot that will not parse is
 * moved aside with a timestamp rather than deleted, so an operator can still recover the numbers,
 * and the board starts fresh instead of refusing to load.
 */
public final class LeaderboardSnapshotStore {
    public static final String FOLDER = "leaderboards/state";

    private final Path folder;

    public LeaderboardSnapshotStore(File dataFolder) {
        this.folder = dataFolder.toPath().resolve(FOLDER);
    }

    private Path file(String id) {
        return folder.resolve(id + ".json");
    }

    public LeaderboardSnapshot load(String id) {
        Path path = file(id);
        if (!Files.isRegularFile(path)) {
            return LeaderboardSnapshot.empty(id);
        }
        try {
            LeaderboardSnapshot snapshot = DocumentParsers.GSON.fromJson(
                Files.readString(path, StandardCharsets.UTF_8), LeaderboardSnapshot.class);
            if (snapshot != null && snapshot.id() != null) {
                return snapshot;
            }
            throw new IOException("snapshot is empty");
        } catch (IOException | RuntimeException failure) {
            quarantine(path, failure);
            return LeaderboardSnapshot.empty(id);
        }
    }

    public void save(LeaderboardSnapshot snapshot) throws IOException {
        Path path = file(snapshot.id());
        AtomicFiles.createParentDirectories(path);
        AtomicFiles.replace(path, (DocumentParsers.GSON.toJson(snapshot) + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8));
    }

    private static void quarantine(Path path, Throwable failure) {
        Path kept = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(path, kept, StandardCopyOption.REPLACE_EXISTING);
            Gloss.logExceptionStack(false, failure,
                "Leaderboard snapshot %s could not be read; kept as %s and the board restarted.",
                path.getFileName(), kept.getFileName());
        } catch (IOException moveFailure) {
            Gloss.logExceptionStack(false, moveFailure,
                "Leaderboard snapshot %s could not be read or set aside.", path.getFileName());
        }
    }
}
