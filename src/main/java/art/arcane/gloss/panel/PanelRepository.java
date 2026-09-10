package art.arcane.gloss.panel;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.volmlib.util.io.FolderWatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public final class PanelRepository implements PanelStore {
  public static final String DIRECTORY_NAME = "panels";

  private static final String JSON_EXTENSION = ".json";
  private static final String NOUN = "panel";
  private static final int POLL_FILE_LIMIT = 32;
  private static final long POLL_BYTE_LIMIT = 8L * 1024L * 1024L;
  private static final long POLL_TIME_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
  private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
  private static final long RECONCILIATION_NANOS = TimeUnit.SECONDS.toNanos(6L);
  private static final long DELETION_GRACE_NANOS = TimeUnit.SECONDS.toNanos(3L);
  private static final Gson GSON = new GsonBuilder()
      .serializeNulls()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create();

  private final Path directory;
  private final Map<String, PanelDefinition> boards = new HashMap<>();
  private final LongSupplier clock;
  private final Map<Path, String> observedFiles = new HashMap<>();
  private final Map<Path, PendingFile> pendingFiles = new LinkedHashMap<>();
  private final Map<Path, String> reportedFailures = new HashMap<>();
  private final LinkedHashSet<Path> queuedFiles = new LinkedHashSet<>();
  private FolderWatcher watcher;
  private long nextReconciliation;
  private boolean closed;

  public PanelRepository(File pluginDataDirectory) {
    this(Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory").toPath());
  }

  public PanelRepository(Path pluginDataDirectory) {
    this(pluginDataDirectory, System::nanoTime);
  }

  PanelRepository(Path pluginDataDirectory, LongSupplier clock) {
    Path dataDirectory = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory").toAbsolutePath().normalize();
    this.directory = dataDirectory.resolve(DIRECTORY_NAME).normalize();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Path directory() {
    return directory;
  }

  /**
   * Reads whatever {@code panels/} holds. A missing folder loads nothing rather than creating one:
   * the folder is made by the first panel that is written, so a server that never authors a panel
   * never grows the directory.
   */
  @Override
  public synchronized PanelLoadResult load() throws IOException {
    requireOpen();
    Map<String, PanelDefinition> previous = new HashMap<>(boards);
    Map<String, PanelDefinition> loadedBoards = new HashMap<>();
    Map<UUID, String> loadedUuids = new HashMap<>();
    Map<UUID, String> previousUuidOwners = new HashMap<>();
    previous.forEach((id, definition) -> previousUuidOwners.put(definition.uuid(), id));
    Map<String, String> failures = new TreeMap<>();
    int loaded = 0;
    int retained = 0;
    Map<Path, String> loadedFiles = new HashMap<>();

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

      PanelFile captured = capture(file);
      try {
        if (Files.isSymbolicLink(file)) {
          throw new IOException("symbolic-link panel files are not allowed");
        }
        PanelDefinition definition = parse(captured);
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
        loadedFiles.put(file, captured.fingerprint());
        loaded++;
      } catch (IOException | RuntimeException failure) {
        if (!DocumentEnvelope.isUnsupportedSchemaVersion(failure)) {
          failures.put(relative, message(failure));
          reportFailure(file, captured.fingerprint(), failure);
        }
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
    observedFiles.clear();
    observedFiles.putAll(loadedFiles);
    pendingFiles.clear();
    queuedFiles.clear();
    resetWatcher();
    return new PanelLoadResult(loaded, retained, removed, failures);
  }

  @Override
  public synchronized DocumentDelta poll() throws IOException {
    if (closed) {
      return DocumentDelta.EMPTY;
    }
    if (watcher == null) {
      resetWatcher();
      nextReconciliation = 0L;
    }
    long now = clock.getAsLong();
    if (watcher.checkModifiedEvents()) {
      queueWatched(watcher.getCreated());
      queueWatched(watcher.getChanged());
      queueWatched(watcher.getDeleted());
    }
    if (now >= nextReconciliation) {
      queuedFiles.addAll(boardFiles());
      queuedFiles.addAll(observedFiles.keySet());
      queuedFiles.addAll(reportedFailures.keySet());
      for (String id : boards.keySet()) {
        queuedFiles.add(pathForCanonical(id));
      }
      nextReconciliation = now + RECONCILIATION_NANOS;
    }
    queuedFiles.addAll(pendingFiles.keySet());
    LinkedHashSet<Path> candidates = new LinkedHashSet<>();
    for (Path file : queuedFiles) {
      if (candidates.size() == POLL_FILE_LIMIT) {
        break;
      }
      candidates.add(file);
    }
    queuedFiles.removeAll(candidates);
    List<String> loaded = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    long bytes = 0L;
    int processed = 0;
    for (Path file : candidates) {
      if (processed > 0 && (bytes + estimatedSize(file) > POLL_BYTE_LIMIT
          || clock.getAsLong() - now >= POLL_TIME_NANOS)) {
        queuedFiles.add(file);
        continue;
      }
      PanelFile captured = capture(file);
      bytes += captured.bytes();
      processed++;
      pollFile(file, captured, now, loaded, removed);
    }
    return loaded.isEmpty() && removed.isEmpty()
        ? DocumentDelta.EMPTY : new DocumentDelta(loaded, removed);
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (watcher != null) {
      watcher.close();
      watcher = null;
    }
    pendingFiles.clear();
    queuedFiles.clear();
    observedFiles.clear();
    reportedFailures.clear();
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

    String raw = writeAtomic(target, created, false);
    boards.put(created.id(), created);
    remember(created, raw);
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
      throw new DocumentRevisionConflictException(NOUN, canonicalId, expectedRevision, current.revision());
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
    String raw = writeAtomic(target, next, true);
    boards.put(canonicalId, next);
    remember(next, raw);
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
      throw new DocumentRevisionConflictException(NOUN, canonicalId, expectedRevision, current.revision());
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
        current.revision() + 1L, current.rootMenuId(), current.transform(), current.follow(), current.visibility(), current.show());
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

    String raw = writeAtomic(target, renamed, false);
    boolean sourceDeleted = false;
    try {
      Files.delete(source);
      sourceDeleted = true;
      AtomicFiles.forceDirectory(source.getParent());
    } catch (IOException failure) {
      try {
        if (sourceDeleted) {
          writeAtomic(source, current, false);
        }
        Files.deleteIfExists(target);
        AtomicFiles.forceDirectory(target.getParent());
      } catch (IOException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }

    boards.remove(canonicalId);
    boards.put(canonicalNewId, renamed);
    forget(source);
    remember(renamed, raw);
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
      throw new DocumentRevisionConflictException(NOUN, canonicalId, expectedRevision, current.revision());
    }

    Path target = pathForCanonical(canonicalId);
    validateAncestors(target);
    if (Files.isSymbolicLink(target)) {
      throw new IOException("refusing to delete symbolic-link panel file: " + target);
    }
    Files.deleteIfExists(target);
    AtomicFiles.forceDirectory(target.getParent());
    boards.remove(canonicalId);
    forget(target);
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
    PanelFile captured = capture(target);
    PanelDefinition persisted = parse(captured);
    if (!persisted.equals(requiredCreated)) {
      throw new IOException("external panel creation does not match the persisted document");
    }
    boards.put(requiredCreated.id(), requiredCreated);
    remember(requiredCreated, captured.raw());
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
    forget(target);
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
      throw new DocumentRevisionConflictException(NOUN, requiredExpected.id(),
          requiredExpected.revision(), actualRevision);
    }
    validateUpdateIdentity(current, new PanelDefinition(
        requiredUpdated.schemaVersion(), requiredUpdated.id(), requiredUpdated.uuid(),
        current.revision(), requiredUpdated.rootMenuId(), requiredUpdated.transform(),
        requiredUpdated.follow(), requiredUpdated.visibility(), requiredUpdated.show()));
    if (requiredUpdated.revision() != current.revision() + 1L) {
      throw new IllegalArgumentException("external panel publication must advance revision exactly once");
    }
    Path target = pathForCanonical(current.id());
    validateAncestors(target);
    PanelFile captured = capture(target);
    PanelDefinition persisted = parse(captured);
    if (!persisted.equals(requiredUpdated)) {
      throw new IOException("external panel publication does not match the persisted document");
    }
    boards.put(requiredUpdated.id(), requiredUpdated);
    remember(requiredUpdated, captured.raw());
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
    PanelFile captured = capture(target);
    PanelDefinition persisted = parse(captured);
    if (!persisted.equals(requiredRestored)) {
      throw new IOException("recovered panel file does not match the transaction backup");
    }
    boards.put(requiredRestored.id(), requiredRestored);
    remember(requiredRestored, captured.raw());
    return requiredRestored;
  }

  private List<Path> boardFiles() throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    AtomicFiles.requireRealDirectory(directory, NOUN);
    return boardFiles(directory);
  }

  private List<Path> boardFiles(Path start) throws IOException {
    try (Stream<Path> paths = Files.walk(start)) {
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

  private PanelDefinition parse(PanelFile captured) throws IOException {
    if (captured.failure() != null) {
      throw captured.failure();
    }
    if (captured.missing()) {
      throw new NoSuchFileException("panel file disappeared before it could be read");
    }
    JsonElement element = JsonParser.parseString(captured.raw());
    if (!element.isJsonObject()) {
      throw new IllegalArgumentException("panel document must be a JSON object");
    }
    PanelDefinition definition = GSON.fromJson(element, PanelDefinition.class);
    if (definition == null) {
      throw new IllegalArgumentException("panel document must not be null");
    }
    return definition;
  }

  private void queueWatched(List<File> files) throws IOException {
    for (File changed : files) {
      Path file = changed.toPath().toAbsolutePath().normalize();
      if (!file.startsWith(directory)) {
        continue;
      }
      if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
        queuedFiles.addAll(boardFiles(file));
      } else if (hasJsonExtension(file)) {
        queuedFiles.add(file);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
      }
      for (String id : boards.keySet()) {
        Path published = pathForCanonical(id);
        if (published.startsWith(file)) {
          queuedFiles.add(published);
        }
      }
    }
  }

  private long estimatedSize(Path file) {
    try {
      return Math.min(MAX_DOCUMENT_BYTES, Files.size(file));
    } catch (IOException failure) {
      return 0L;
    }
  }

  private PanelFile capture(Path file) {
    byte[] bytes = null;
    try {
      Path ancestor = directory;
      for (Path segment : directory.relativize(file)) {
        BasicFileAttributes attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
          throw new IOException("panel storage path must be a real directory: " + ancestor);
        }
        ancestor = ancestor.resolve(segment);
      }
      BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile() || before.isSymbolicLink()) {
        throw new IOException("panel source must be a regular non-symbolic file: " + file);
      }
      if (before.size() > MAX_DOCUMENT_BYTES) {
        throw new IOException("panel document exceeds " + MAX_DOCUMENT_BYTES + " bytes: " + file);
      }
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
      }
      BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile() || bytes.length > MAX_DOCUMENT_BYTES
          || bytes.length != before.size() || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())) {
        throw new IOException("panel source changed while being captured: " + file);
      }
      String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      return new PanelFile(raw, DocumentHashes.sha256(bytes), null, false, bytes.length);
    } catch (NoSuchFileException missing) {
      return new PanelFile(null, "missing", null, true, bytes == null ? 0 : bytes.length);
    } catch (IOException failure) {
      String fingerprint = bytes == null ? failure.getClass().getName() + ":" + message(failure)
          : DocumentHashes.sha256(bytes);
      return new PanelFile(null, fingerprint, failure, false, bytes == null ? 0 : bytes.length);
    }
  }

  private void pollFile(Path file, PanelFile captured, long now, List<String> loaded, List<String> removed) {
    if (captured.fingerprint().equals(observedFiles.get(file))) {
      pendingFiles.remove(file);
      reportedFailures.remove(file);
      return;
    }
    PendingFile previous = pendingFiles.get(file);
    if (previous == null || !previous.fingerprint().equals(captured.fingerprint())) {
      pendingFiles.remove(file);
      pendingFiles.put(file, new PendingFile(captured.fingerprint(), now));
      return;
    }
    if (captured.missing() && now - previous.sinceNanos() < DELETION_GRACE_NANOS) {
      return;
    }
    pendingFiles.remove(file);
    try {
      String relative = relativeName(file);
      String pathId = relative.substring(0, relative.length() - JSON_EXTENSION.length());
      String id = PanelIds.canonicalize(pathId);
      if (!pathId.equals(id) || !file.equals(pathForCanonical(id))) {
        throw new IllegalArgumentException("panel file path is not canonical");
      }
      if (captured.missing()) {
        if (boards.remove(id) != null) {
          removed.add(id);
          nextReconciliation = 0L;
        }
        forget(file);
        return;
      }
      PanelDefinition definition = parse(captured);
      if (!definition.id().equals(id)) {
        throw new IllegalArgumentException("file id " + id + " does not match document id " + definition.id());
      }
      PanelDefinition current = boards.get(id);
      validateReload(current, definition);
      ensureUniqueUuid(definition.uuid(), id);
      if (!definition.equals(current)) {
        boards.put(id, definition);
        loaded.add(id);
      }
      remember(definition, captured.raw());
    } catch (IOException | RuntimeException failure) {
      if (!DocumentEnvelope.isUnsupportedSchemaVersion(failure)) {
        reportFailure(file, captured.fingerprint(), failure);
      }
    }
  }

  private void remember(PanelDefinition definition, String raw) {
    Path file = pathForCanonical(definition.id());
    observedFiles.put(file, DocumentHashes.sha256(raw));
    pendingFiles.remove(file);
    queuedFiles.remove(file);
    reportedFailures.remove(file);
  }

  private void forget(Path file) {
    observedFiles.remove(file);
    pendingFiles.remove(file);
    queuedFiles.remove(file);
    reportedFailures.remove(file);
  }

  private void reportFailure(Path file, String fingerprint, Throwable failure) {
    if (!fingerprint.equals(reportedFailures.put(file, fingerprint))) {
      Gloss.logExceptionStack(false, failure, "Panel file '%s' was not loaded; keeping its last-good definition.", relativeName(file));
    }
  }

  private void resetWatcher() {
    if (watcher != null) {
      watcher.close();
    }
    watcher = new FolderWatcher(directory.toFile());
    nextReconciliation = clock.getAsLong() + RECONCILIATION_NANOS;
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("panel repository is closed");
    }
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

  private void prepareTarget(Path target) throws IOException {
    AtomicFiles.prepareParent(directory, target, NOUN);
  }

  private void validateAncestors(Path target) throws IOException {
    AtomicFiles.validateAncestors(directory, target, NOUN);
  }

  private String writeAtomic(Path target, PanelDefinition definition, boolean replaceExisting) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("panel file has no parent directory: " + target);
    }
    String raw = encode(definition);
    byte[] encoded = raw.getBytes(StandardCharsets.UTF_8);
    Path temporary = AtomicFiles.writeDurableTemporary(parent, "." + target.getFileName() + ".", encoded);
    try {
      if (replaceExisting) {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      }
      AtomicFiles.forceDirectory(parent);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return raw;
  }

  private static String message(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static String encode(PanelDefinition definition) {
    return GSON.toJson(definition) + System.lineSeparator();
  }

  private record PanelFile(String raw, String fingerprint, IOException failure, boolean missing, int bytes) {
  }

  private record PendingFile(String fingerprint, long sinceNanos) {
  }
}
