package art.arcane.gloss.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public final class PanelRepository implements PanelStore {
  public static final String DIRECTORY_NAME = "panels";

  private static final String JSON_EXTENSION = ".json";
  private static final Gson GSON = new GsonBuilder()
      .serializeNulls()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create();

  private final Path directory;
  private final Map<String, PanelDefinition> boards = new HashMap<>();

  public PanelRepository(File pluginDataDirectory) {
    this(Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory").toPath());
  }

  public PanelRepository(Path pluginDataDirectory) {
    Path dataDirectory = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory").toAbsolutePath().normalize();
    this.directory = dataDirectory.resolve(DIRECTORY_NAME).normalize();
  }

  @Override
  public Path directory() {
    return directory;
  }

  @Override
  public synchronized PanelLoadResult load() throws IOException {
    ensureStorageDirectory();

    Map<String, PanelDefinition> previous = new HashMap<>(boards);
    Map<String, PanelDefinition> loadedBoards = new HashMap<>();
    Map<UUID, String> loadedUuids = new HashMap<>();
    Map<UUID, String> previousUuidOwners = new HashMap<>();
    previous.forEach((id, definition) -> previousUuidOwners.put(definition.uuid(), id));
    Map<String, String> failures = new TreeMap<>();
    int loaded = 0;
    int retained = 0;

    for (Path file : boardFiles()) {
      String relative = relativeName(file);
      String pathId = relative.substring(0, relative.length() - JSON_EXTENSION.length());
      String id;
      try {
        id = PanelIds.canonicalize(pathId);
        if (!pathId.equals(id) || !file.equals(pathForCanonical(id))) {
          throw new IllegalArgumentException("panel file path is not canonical");
        }
      } catch (RuntimeException failure) {
        failures.put(relative, message(failure));
        continue;
      }

      try {
        if (Files.isSymbolicLink(file)) {
          throw new IOException("symbolic-link panel files are not allowed");
        }
        PanelDefinition definition = read(file);
        if (!definition.id().equals(id)) {
          throw new IllegalArgumentException("file id " + id + " does not match document id " + definition.id());
        }
        validateReload(previous.get(id), definition);
        String previousOwner = previousUuidOwners.get(definition.uuid());
        if (previousOwner != null && !previousOwner.equals(id)) {
          throw new IllegalArgumentException("panel uuid is reserved by " + previousOwner);
        }
        String uuidOwner = loadedUuids.putIfAbsent(definition.uuid(), id);
        if (uuidOwner != null) {
          throw new IllegalArgumentException("panel uuid is already used by " + uuidOwner);
        }
        loadedBoards.put(id, definition);
        loaded++;
      } catch (IOException | RuntimeException failure) {
        failures.put(relative, message(failure));
        PanelDefinition lastGood = previous.get(id);
        if (lastGood != null && loadedUuids.putIfAbsent(lastGood.uuid(), id) == null) {
          loadedBoards.put(id, lastGood);
          retained++;
        }
      }
    }

    int removed = 0;
    for (String id : previous.keySet()) {
      if (!loadedBoards.containsKey(id)) {
        removed++;
      }
    }

    boards.clear();
    boards.putAll(loadedBoards);
    return new PanelLoadResult(loaded, retained, removed, failures);
  }

  @Override
  public synchronized Optional<PanelDefinition> get(String id) {
    return Optional.ofNullable(boards.get(PanelIds.canonicalize(id)));
  }

  @Override
  public synchronized List<PanelDefinition> list() {
    List<PanelDefinition> snapshot = new ArrayList<>(boards.values());
    snapshot.sort(Comparator.comparing(PanelDefinition::id));
    return List.copyOf(snapshot);
  }

  @Override
  public synchronized PanelDefinition create(PanelDefinition definition) throws IOException {
    PanelDefinition created = Objects.requireNonNull(definition, "definition");
    if (created.revision() != PanelDefinition.INITIAL_REVISION) {
      throw new IllegalArgumentException("new panels must start at revision " + PanelDefinition.INITIAL_REVISION);
    }
    if (boards.containsKey(created.id())) {
      throw new FileAlreadyExistsException(created.id());
    }
    ensureUniqueUuid(created.uuid(), created.id());

    Path target = pathForCanonical(created.id());
    prepareTarget(target);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(target.toString());
    }

    writeAtomic(target, created, false);
    boards.put(created.id(), created);
    return created;
  }

  @Override
  public synchronized PanelDefinition update(String id, long expectedRevision,
                                             UnaryOperator<PanelDefinition> update) throws IOException {
    String canonicalId = PanelIds.canonicalize(id);
    PanelDefinition current = boards.get(canonicalId);
    if (current == null) {
      throw new NoSuchElementException("unknown panel: " + canonicalId);
    }
    if (current.revision() != expectedRevision) {
      throw new PanelRevisionConflictException(canonicalId, expectedRevision, current.revision());
    }
    if (current.revision() == PanelDefinition.MAX_SAFE_REVISION) {
      throw new IllegalStateException("panel revision overflow: " + canonicalId);
    }

    PanelDefinition changed = Objects.requireNonNull(
        Objects.requireNonNull(update, "update").apply(current),
        "update result");
    validateUpdateIdentity(current, changed);
    PanelDefinition next = changed.withRevision(current.revision() + 1L);

    Path target = pathForCanonical(canonicalId);
    prepareTarget(target);
    writeAtomic(target, next, true);
    boards.put(canonicalId, next);
    return next;
  }

  @Override
  public synchronized PanelDefinition rename(String id, String newId, long expectedRevision) throws IOException {
    String canonicalId = PanelIds.canonicalize(id);
    String canonicalNewId = PanelIds.canonicalize(newId);
    PanelDefinition current = boards.get(canonicalId);
    if (current == null) {
      throw new NoSuchElementException("unknown panel: " + canonicalId);
    }
    if (current.revision() != expectedRevision) {
      throw new PanelRevisionConflictException(canonicalId, expectedRevision, current.revision());
    }
    if (canonicalId.equals(canonicalNewId)) {
      throw new IllegalArgumentException("new panel id must differ from the current id");
    }
    if (current.revision() == PanelDefinition.MAX_SAFE_REVISION) {
      throw new IllegalStateException("panel revision overflow: " + canonicalId);
    }
    if (boards.containsKey(canonicalNewId)) {
      throw new FileAlreadyExistsException(canonicalNewId);
    }

    PanelDefinition renamed = new PanelDefinition(current.schemaVersion(), canonicalNewId, current.uuid(),
        current.revision() + 1L, current.rootMenuId(), current.transform(), current.follow(), current.visibility());
    Path source = pathForCanonical(canonicalId);
    Path target = pathForCanonical(canonicalNewId);
    prepareTarget(source);
    prepareTarget(target);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    if (Files.isSymbolicLink(source)) {
      throw new IOException("refusing to rename symbolic-link panel file: " + source);
    }

    writeAtomic(target, renamed, false);
    boolean sourceDeleted = false;
    try {
      Files.delete(source);
      sourceDeleted = true;
      forceDirectory(source.getParent());
    } catch (IOException failure) {
      try {
        if (sourceDeleted) {
          writeAtomic(source, current, false);
        }
        Files.deleteIfExists(target);
        forceDirectory(target.getParent());
      } catch (IOException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }

    boards.remove(canonicalId);
    boards.put(canonicalNewId, renamed);
    return renamed;
  }

  @Override
  public synchronized PanelDefinition delete(String id, long expectedRevision) throws IOException {
    String canonicalId = PanelIds.canonicalize(id);
    PanelDefinition current = boards.get(canonicalId);
    if (current == null) {
      throw new NoSuchElementException("unknown panel: " + canonicalId);
    }
    if (current.revision() != expectedRevision) {
      throw new PanelRevisionConflictException(canonicalId, expectedRevision, current.revision());
    }

    Path target = pathForCanonical(canonicalId);
    validateAncestors(target);
    if (Files.isSymbolicLink(target)) {
      throw new IOException("refusing to delete symbolic-link panel file: " + target);
    }
    Files.deleteIfExists(target);
    forceDirectory(target.getParent());
    boards.remove(canonicalId);
    return current;
  }

  @Override
  public synchronized PanelDefinition publishExternalCreate(PanelDefinition created) throws IOException {
    PanelDefinition requiredCreated = Objects.requireNonNull(created, "created");
    if (requiredCreated.revision() != PanelDefinition.INITIAL_REVISION) {
      throw new IllegalArgumentException(
          "external panel creation must start at revision " + PanelDefinition.INITIAL_REVISION);
    }
    if (boards.containsKey(requiredCreated.id())) {
      throw new FileAlreadyExistsException(requiredCreated.id());
    }
    ensureUniqueUuid(requiredCreated.uuid(), requiredCreated.id());
    Path target = pathForCanonical(requiredCreated.id());
    validateAncestors(target);
    PanelDefinition persisted = read(target);
    if (!persisted.equals(requiredCreated)) {
      throw new IOException("external panel creation does not match the persisted document");
    }
    boards.put(requiredCreated.id(), requiredCreated);
    return requiredCreated;
  }

  @Override
  public synchronized PanelDefinition recoverExternalCreate(PanelDefinition created) throws IOException {
    PanelDefinition requiredCreated = Objects.requireNonNull(created, "created");
    PanelDefinition current = boards.get(requiredCreated.id());
    if (current == null) {
      return requiredCreated;
    }
    if (!requiredCreated.equals(current)) {
      throw new IOException("cannot recover panel creation after an unrelated publication");
    }
    Path target = pathForCanonical(requiredCreated.id());
    validateAncestors(target);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("recovered panel creation file still exists: " + target);
    }
    boards.remove(requiredCreated.id());
    return requiredCreated;
  }

  @Override
  public synchronized PanelDefinition publishExternal(PanelDefinition expected,
                                                       PanelDefinition updated) throws IOException {
    PanelDefinition requiredExpected = Objects.requireNonNull(expected, "expected");
    PanelDefinition requiredUpdated = Objects.requireNonNull(updated, "updated");
    PanelDefinition current = boards.get(requiredExpected.id());
    if (!requiredExpected.equals(current)) {
      long actualRevision = current == null ? 0L : current.revision();
      throw new PanelRevisionConflictException(requiredExpected.id(),
          requiredExpected.revision(), actualRevision);
    }
    validateUpdateIdentity(current, new PanelDefinition(
        requiredUpdated.schemaVersion(), requiredUpdated.id(), requiredUpdated.uuid(),
        current.revision(), requiredUpdated.rootMenuId(), requiredUpdated.transform(),
        requiredUpdated.follow(), requiredUpdated.visibility()));
    if (requiredUpdated.revision() != current.revision() + 1L) {
      throw new IllegalArgumentException("external panel publication must advance revision exactly once");
    }
    Path target = pathForCanonical(current.id());
    validateAncestors(target);
    PanelDefinition persisted = read(target);
    if (!persisted.equals(requiredUpdated)) {
      throw new IOException("external panel publication does not match the persisted document");
    }
    boards.put(requiredUpdated.id(), requiredUpdated);
    return requiredUpdated;
  }

  @Override
  public synchronized PanelDefinition recoverExternal(PanelDefinition applied,
                                                       PanelDefinition restored) throws IOException {
    PanelDefinition requiredApplied = Objects.requireNonNull(applied, "applied");
    PanelDefinition requiredRestored = Objects.requireNonNull(restored, "restored");
    PanelDefinition current = boards.get(requiredApplied.id());
    if (requiredRestored.equals(current)) {
      return requiredRestored;
    }
    if (!requiredApplied.equals(current)) {
      throw new IOException("cannot recover panel state after an unrelated publication");
    }
    Path target = pathForCanonical(requiredRestored.id());
    validateAncestors(target);
    PanelDefinition persisted = read(target);
    if (!persisted.equals(requiredRestored)) {
      throw new IOException("recovered panel file does not match the transaction backup");
    }
    boards.put(requiredRestored.id(), requiredRestored);
    return requiredRestored;
  }

  private List<Path> boardFiles() throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths
          .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(this::hasJsonExtension)
          .sorted(Comparator.comparing(this::relativeName))
          .toList();
    }
  }

  private boolean hasJsonExtension(Path path) {
    Path fileName = path.getFileName();
    return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION);
  }

  private String relativeName(Path path) {
    return directory.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
  }

  private PanelDefinition read(Path file) throws IOException {
    String json = Files.readString(file, StandardCharsets.UTF_8);
    JsonElement element = JsonParser.parseString(json);
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("panel document must be a JSON object");
    }
    PanelDefinition definition = GSON.fromJson(element, PanelDefinition.class);
    if (definition == null) {
      throw new IllegalArgumentException("panel document must not be null");
    }
    return definition;
  }

  private void validateReload(PanelDefinition previous, PanelDefinition loaded) {
    if (previous == null) {
      return;
    }
    if (!previous.uuid().equals(loaded.uuid())) {
      throw new IllegalArgumentException("panel uuid cannot change across reloads");
    }
    if (loaded.revision() < previous.revision()) {
      throw new IllegalArgumentException("panel revision cannot move backwards");
    }
    if (loaded.revision() == previous.revision() && !loaded.equals(previous)) {
      throw new IllegalArgumentException("panel content changed without a revision increment");
    }
  }

  private void validateUpdateIdentity(PanelDefinition current, PanelDefinition changed) {
    if (!changed.id().equals(current.id())) {
      throw new IllegalArgumentException("an update cannot change the panel id");
    }
    if (!changed.uuid().equals(current.uuid())) {
      throw new IllegalArgumentException("an update cannot change the panel uuid");
    }
    if (changed.schemaVersion() != current.schemaVersion()) {
      throw new IllegalArgumentException("an update cannot change schemaVersion");
    }
    if (changed.revision() != current.revision()) {
      throw new IllegalArgumentException("the repository owns revision increments");
    }
  }

  private void ensureUniqueUuid(UUID uuid, String id) {
    for (PanelDefinition board : boards.values()) {
      if (board.uuid().equals(uuid) && !board.id().equals(id)) {
        throw new IllegalArgumentException("panel uuid is already used by " + board.id());
      }
    }
  }

  private Path pathForCanonical(String id) {
    Path target = directory.resolve(id + JSON_EXTENSION).normalize();
    if (!target.startsWith(directory)) {
      throw new IllegalArgumentException("panel path escapes the panel directory: " + id);
    }
    return target;
  }

  private void ensureStorageDirectory() throws IOException {
    Path parent = directory.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("panel storage path must be a real directory: " + directory);
      }
      return;
    }
    Files.createDirectory(directory);
    forceDirectory(directory.getParent());
  }

  private void prepareTarget(Path target) throws IOException {
    ensureStorageDirectory();
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("panel file has no parent directory: " + target);
    }

    Path current = directory;
    for (Path segment : directory.relativize(parent)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("panel path contains a non-directory or symbolic link: " + current);
        }
      } else {
        Files.createDirectory(current);
        forceDirectory(current.getParent());
      }
    }

    if (Files.isSymbolicLink(target)) {
      throw new IOException("panel file must not be a symbolic link: " + target);
    }
  }

  private void validateAncestors(Path target) throws IOException {
    Path parent = target.getParent();
    if (parent == null || !parent.startsWith(directory)) {
      throw new IOException("panel path escapes the panel directory: " + target);
    }
    Path current = directory;
    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("panel storage path must be a real directory: " + current);
    }
    for (Path segment : directory.relativize(parent)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("panel path contains a non-directory or symbolic link: " + current);
      }
    }
  }

  private void writeAtomic(Path target, PanelDefinition definition, boolean replaceExisting) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("panel file has no parent directory: " + target);
    }
    Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
    try {
      byte[] encoded = (GSON.toJson(definition) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }

      if (replaceExisting) {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      }
      forceDirectory(parent);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void forceDirectory(Path path) throws IOException {
    if (path == null) {
      return;
    }
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException ignored) {
    }
  }

  private static String message(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }
}
