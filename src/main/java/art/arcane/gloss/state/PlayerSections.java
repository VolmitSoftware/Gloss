package art.arcane.gloss.state;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-player persisted sections in {@code state/players/<uuid>.json}. One file per player, one
 * top-level object per section, so features that own different sections never overwrite each
 * other. Writes replace the whole file atomically; a file that will not parse is renamed aside and
 * read as empty so one bad save never blocks a login.
 *
 * <p>The parsed file is held in memory once it has been touched, because render paths read it
 * every pass. Re-reading and re-parsing the file for each viewer would block the main thread. Memory is authoritative for this process; a write updates it and then persists
 * the whole file, and a write that changes nothing does not touch the disk at all. Entries are
 * dropped on quit through {@link #evict(UUID)}.
 */
public final class PlayerSections {
    private static final Type FILE_TYPE = new TypeToken<Map<String, Map<String, Object>>>() {
    }.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;
    private final ConcurrentMap<UUID, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Map<String, Map<String, Object>>> cached = new ConcurrentHashMap<>();

    public PlayerSections(Path dataFolder) {
        this.root = Objects.requireNonNull(dataFolder, "dataFolder").resolve("state").resolve("players");
    }

    public Map<String, Object> read(UUID player, String section) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(section, "section");
        synchronized (lock(player)) {
            Map<String, Object> values = load(player).get(section);
            return values == null ? Map.of() : Map.copyOf(values);
        }
    }

    public void write(UUID player, String section, Map<String, Object> values) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(section, "section");
        synchronized (lock(player)) {
            Map<String, Map<String, Object>> file = load(player);
            boolean empty = values == null || values.isEmpty();
            Map<String, Object> present = file.get(section);
            if (empty ? present == null : present != null && present.equals(values)) {
                return;
            }
            if (empty) {
                file.remove(section);
            } else {
                file.put(section, new LinkedHashMap<>(values));
            }
            store(player, file);
        }
    }

    /** Writes are immediate; the method exists so callers match the shared store contract. */
    public void flush() {
    }

    /** Drops a player's cached file. The file itself stays; the next read parses it again. */
    public void evict(UUID player) {
        Objects.requireNonNull(player, "player");
        cached.remove(player);
        locks.remove(player);
    }

    public void forget(UUID player) {
        Objects.requireNonNull(player, "player");
        synchronized (lock(player)) {
            cached.remove(player);
            try {
                Files.deleteIfExists(file(player));
            } catch (IOException failure) {
                Gloss.logExceptionStack(false, failure, "state/players/%s.json could not be deleted.", player);
            }
        }
        locks.remove(player);
    }

    private Map<String, Map<String, Object>> load(UUID player) {
        Map<String, Map<String, Object>> known = cached.get(player);
        if (known != null) {
            return known;
        }
        Map<String, Map<String, Object>> parsed = parse(player);
        cached.put(player, parsed);
        return parsed;
    }

    private Map<String, Map<String, Object>> parse(UUID player) {
        Path file = file(player);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, Object>> parsed = GSON.fromJson(
                Files.readString(file, StandardCharsets.UTF_8), FILE_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonSyntaxException | IOException | IllegalStateException failure) {
            quarantine(file, failure);
            return new LinkedHashMap<>();
        }
    }

    private void store(UUID player, Map<String, Map<String, Object>> file) {
        Path target = file(player);
        try {
            AtomicFiles.createParentDirectories(target);
            AtomicFiles.replace(target, GSON.toJson(file, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "state/players/%s.json could not be written.", player);
        }
    }

    private void quarantine(Path file, Throwable failure) {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            Gloss.logExceptionStack(false, moveFailure, "%s could not be quarantined.", file);
            return;
        }
        Gloss.logExceptionStack(false, failure, "%s did not parse; it was renamed to %s and read as empty.",
            file.getFileName(), aside.getFileName());
    }

    private Path file(UUID player) {
        return root.resolve(player + ".json");
    }

    private Object lock(UUID player) {
        return locks.computeIfAbsent(player, ignored -> new Object());
    }
}
