package art.arcane.gloss.persistence;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.panel.PanelRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GlossProjectTransaction {
  private static final int JOURNAL_VERSION = 2;
  private static final int MAX_BACKUPS = 20;
  private static final long MAX_JOURNAL_BYTES = 1024L * 1024L;
  private static final Pattern TRANSACTION_DIRECTORY = Pattern.compile(
      "[0-9]{10,}-[A-Za-z0-9_-]{1,64}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Gson GSON = new GsonBuilder()
      .serializeNulls()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create();
  private final Path dataDirectory;
  private final Path transactionsDirectory;
  private final Path backupsDirectory;
  private final DirectoryForceProbe directoryForceProbe;
  private final Set<String> uncertainCommits;

  public GlossProjectTransaction(Path dataDirectory) {
    this(dataDirectory, directory -> {
    });
  }

  GlossProjectTransaction(Path dataDirectory, DirectoryForceProbe directoryForceProbe) {
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize();
    this.transactionsDirectory = this.dataDirectory.resolve("editor-sync-transactions");
    this.backupsDirectory = this.dataDirectory.resolve("editor-sync-backups");
    this.directoryForceProbe = Objects.requireNonNull(directoryForceProbe, "directoryForceProbe");
    this.uncertainCommits = new HashSet<>();
  }

  /**
   * Resolves whatever the previous run left behind. Neither root is created here: a server that has
   * never run an editor sync has nothing to recover, and creating the pair on every boot left two
   * permanently empty folders in the data directory. {@link #apply} still creates them, because it
   * is about to write into them.
   */
  public void recover() throws IOException {
    if (!Files.exists(transactionsDirectory, LinkOption.NOFOLLOW_LINKS)
        && !Files.exists(backupsDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    List<Path> transactions = childDirectories(transactionsDirectory);
    for (Path transaction : transactions) {
      if (!Files.exists(transaction.resolve("journal.json"), LinkOption.NOFOLLOW_LINKS)) {
        cleanupUnjournaled(transaction);
        continue;
      }
      Journal journal = readJournal(transaction);
      if (journal.state().equals("committed")) {
        archive(transaction, journal.id());
      } else {
        rollback(transaction, journal);
        writeJournal(transaction, journal.withState("rolledback"));
        archive(transaction, journal.id());
      }
    }
    pruneBackups();
  }

  public Pending apply(String transactionId, Map<Path, Mutation> requestedMutations,
                       Map<Path, byte[]> expectedExisting) throws IOException {
    ensureRootDirectories();
    String label = safeLabel(transactionId);
    String id = Instant.now().toEpochMilli() + "-" + label + "-" + UUID.randomUUID();
    LinkedHashMap<Path, Mutation> mutations = orderedMutations(requestedMutations);
    if (mutations.isEmpty()) {
      throw new IllegalArgumentException("editor sync transaction has no mutations");
    }
    Path transaction = transactionsDirectory.resolve(id).normalize();
    createChildDirectory(transactionsDirectory, transaction);
    Path stage = transaction.resolve("stage");
    // Created by the first replacement that overwrites an existing file. A publication that is all
    // new files backs nothing up, and would otherwise archive an empty backup/ forever.
    Path backup = transaction.resolve("backup");
    List<Entry> entries = new ArrayList<>(mutations.size());
    try {
      createChildDirectory(transaction, stage);
      for (Map.Entry<Path, Mutation> mutationEntry : mutations.entrySet()) {
        Path target = mutationEntry.getKey();
        Mutation mutation = mutationEntry.getValue();
        validateTarget(target);
        Path relative = dataDirectory.relativize(target);
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (mutation.operation() == Operation.DELETE && !existed) {
          throw new IOException("editor sync delete target does not exist: " + target);
        }
        String stagedHash = null;
        if (mutation.operation() == Operation.WRITE) {
          Path staged = stage.resolve(relative).normalize();
          safePrepareParent(stage, staged);
          writeNewFile(staged, mutation.content());
          stagedHash = DocumentHashes.sha256(mutation.content());
        }
        String originalHash = null;
        if (existed) {
          byte[] original = readRegularFile(target);
          originalHash = DocumentHashes.sha256(original);
          ensureChildDirectory(transaction, backup);
          Path backupFile = backup.resolve(relative).normalize();
          safePrepareParent(backup, backupFile);
          writeNewFile(backupFile, original);
        }
        entries.add(new Entry(relativePath(relative), mutation.operation(), existed,
            originalHash, stagedHash));
      }
    } catch (IOException | RuntimeException failure) {
      try {
        cleanupUnjournaled(transaction);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }

    Journal prepared = new Journal(JOURNAL_VERSION, id, "prepared", List.copyOf(entries));
    writeJournal(transaction, prepared);
    try {
      verifyExpected(expectedExisting);
    } catch (IOException | RuntimeException failure) {
      try {
        rollback(transaction, prepared);
        writeJournal(transaction, prepared.withState("rolledback"));
        archive(transaction, id);
      } catch (IOException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }
    Journal publishing = prepared.withState("publishing");
    writeJournal(transaction, publishing);
    try {
      for (Entry entry : entries) {
        Path target = target(entry);
        if (entry.operation() == Operation.WRITE) {
          Path staged = stage.resolve(entry.relativePath()).normalize();
          safePrepareParent(dataDirectory, target);
          Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
          forceFile(target);
        } else {
          Files.delete(target);
        }
        forceDirectory(target.getParent());
      }
      Journal published = prepared.withState("published");
      writeJournal(transaction, published);
      return new Pending(transaction, id, List.copyOf(mutations.keySet()));
    } catch (IOException | RuntimeException failure) {
      try {
        rollback(transaction, publishing);
        writeJournal(transaction, prepared.withState("rolledback"));
        archive(transaction, id);
      } catch (IOException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }
  }

  public void commit(Pending pending) throws IOException {
    Pending required = Objects.requireNonNull(pending, "pending");
    Path transaction = required.transactionDirectory().toAbsolutePath().normalize();
    requireChild(transactionsDirectory, transaction);
    Journal journal = readJournal(transaction);
    if (!journal.id().equals(required.id())) {
      throw new IOException("editor sync transaction is not ready to commit");
    }
    if (journal.state().equals("committed")) {
      if (uncertainCommits.contains(journal.id())) {
        throw new CommitUncertainException(
            "editor sync transaction commit durability is still uncertain",
            new IOException("restart recovery must resolve the commit marker"));
      }
      throw new CommittedCleanupException(
          "editor sync transaction was already durably committed",
          new IOException("committed transaction cleanup is incomplete"));
    }
    if (!journal.state().equals("published")) {
      throw new IOException("editor sync transaction is not ready to commit");
    }
    try {
      writeJournal(transaction, journal.withState("committed"));
    } catch (AtomicReplaceDurabilityException markerFailure) {
      uncertainCommits.add(journal.id());
      throw new CommitUncertainException(
          "editor sync transaction commit marker could not be made durable; restart recovery is required",
          markerFailure);
    } catch (IOException markerFailure) {
      throw markerFailure;
    }
    try {
      archive(transaction, journal.id());
      pruneBackups();
    } catch (IOException cleanupFailure) {
      throw new CommittedCleanupException(
          "editor sync transaction committed but backup cleanup did not finish", cleanupFailure);
    }
  }

  private LinkedHashMap<Path, Mutation> orderedMutations(Map<Path, Mutation> requested) {
    LinkedHashMap<Path, Mutation> mutations = new LinkedHashMap<>();
    Objects.requireNonNull(requested, "requestedMutations").entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(entry -> {
          Path target = entry.getKey().toAbsolutePath().normalize();
          Mutation previous = mutations.put(target,
              Objects.requireNonNull(entry.getValue(), "mutation"));
          if (previous != null) {
            throw new IllegalArgumentException("duplicate editor sync mutation target: " + target);
          }
        });
    return mutations;
  }

  private void verifyExpected(Map<Path, byte[]> expectedExisting) throws IOException {
    for (Map.Entry<Path, byte[]> entry : expectedExisting.entrySet()) {
      Path target = entry.getKey().toAbsolutePath().normalize();
      validateTarget(target);
      byte[] expected = entry.getValue();
      if (expected == null) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("transaction target was created after the session snapshot: " + target);
        }
      } else if (!java.util.Arrays.equals(expected, readRegularFile(target))) {
        throw new IOException("transaction target changed after the session snapshot: " + target);
      }
    }
  }

  private void rollback(Path transaction, Journal journal) throws IOException {
    Path backup = transaction.resolve("backup").normalize();
    Path stage = transaction.resolve("stage").normalize();
    requireChild(transaction, backup);
    IOException failure = null;
    List<Entry> reverse = new ArrayList<>(journal.entries());
    java.util.Collections.reverse(reverse);
    for (Entry entry : reverse) {
      Path target = target(entry);
      try {
        Path stagedFile = stage.resolve(entry.relativePath()).normalize();
        requireChild(stage, stagedFile);
        if (entry.operation() == Operation.WRITE) {
          if (Files.exists(stagedFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!DocumentHashes.sha256(readRegularFile(stagedFile)).equals(entry.stagedHash())) {
              throw new IOException("editor sync staged-file hash mismatch: " + entry.relativePath());
            }
          } else if (journal.state().equals("prepared")) {
            throw new IOException("prepared editor sync transaction is missing a staged file: "
                + entry.relativePath());
          }
        } else if (Files.exists(stagedFile, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("delete mutation unexpectedly has a staged file: "
              + entry.relativePath());
        }
        if (entry.existed()) {
          Path backupFile = backup.resolve(entry.relativePath()).normalize();
          requireChild(backup, backupFile);
          byte[] original = readRegularFile(backupFile);
          if (!DocumentHashes.sha256(original).equals(entry.originalHash())) {
            throw new IOException("editor sync backup hash mismatch: " + entry.relativePath());
          }
          if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (entry.operation() == Operation.DELETE) {
              replaceDurably(target, original);
              continue;
            }
            throw new IOException("editor sync target disappeared during recovery: "
                + entry.relativePath());
          }
          String targetHash = DocumentHashes.sha256(readRegularFile(target));
          if (targetHash.equals(entry.originalHash())) {
            continue;
          }
          if (entry.operation() == Operation.DELETE || !targetHash.equals(entry.stagedHash())) {
            throw new IOException("editor sync target changed independently during recovery: "
                + entry.relativePath());
          }
          replaceDurably(target, original);
        } else {
          if (entry.operation() != Operation.WRITE) {
            throw new IOException("delete mutation has no original file: " + entry.relativePath());
          }
          validateTarget(target);
          if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            String targetHash = DocumentHashes.sha256(readRegularFile(target));
            if (!targetHash.equals(entry.stagedHash())) {
              throw new IOException("new editor sync target changed independently during recovery: "
                  + entry.relativePath());
            }
            Files.delete(target);
            forceDirectory(target.getParent());
          }
        }
      } catch (IOException entryFailure) {
        if (failure == null) {
          failure = new IOException("failed to recover editor sync transaction " + journal.id());
        }
        failure.addSuppressed(entryFailure);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void archive(Path transaction, String id) throws IOException {
    pruneEmptyDirectories(transaction);
    ensureChildDirectory(dataDirectory, backupsDirectory);
    Path destination = backupsDirectory.resolve(id).normalize();
    requireChild(backupsDirectory, destination);
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("editor sync backup already exists: " + destination);
    }
    Files.move(transaction, destination, StandardCopyOption.ATOMIC_MOVE);
    forceDirectory(transactionsDirectory);
    forceDirectory(backupsDirectory);
  }

  private void pruneBackups() throws IOException {
    List<Path> backups = childDirectories(backupsDirectory);
    if (backups.size() <= MAX_BACKUPS) {
      return;
    }
    for (Path backup : backups.subList(0, backups.size() - MAX_BACKUPS)) {
      deleteTree(backup);
      forceDirectory(backupsDirectory);
    }
  }

  private void cleanupUnjournaled(Path transaction) throws IOException {
    requireChild(transactionsDirectory, transaction);
    Path fileName = transaction.getFileName();
    if (fileName == null || !TRANSACTION_DIRECTORY.matcher(fileName.toString()).matches()) {
      throw new IOException("unrecognized editor sync transaction directory: " + transaction);
    }
    try (Stream<Path> paths = Files.walk(transaction)) {
      List<Path> contents = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : contents) {
        if (Files.isSymbolicLink(path)) {
          throw new IOException("unjournaled editor sync transaction contains a symbolic link");
        }
        if (!path.equals(transaction)) {
          Path relative = transaction.relativize(path);
          String first = relative.getName(0).toString();
          if (!first.equals("stage") && !first.equals("backup")) {
            throw new IOException("unjournaled editor sync transaction has unexpected content");
          }
          if (relative.getNameCount() == 1
              && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unjournaled editor sync transaction root entry is not a directory");
          }
          if (relative.getNameCount() >= 2
              && !validUnjournaledEntry(path,
              relative.subpath(1, relative.getNameCount()))) {
            throw new IOException("unjournaled editor sync transaction has an invalid collection");
          }
          if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
              && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unjournaled editor sync transaction has an invalid entry");
          }
        }
      }
      for (Path path : contents) {
        Files.delete(path);
      }
    }
    forceDirectory(transactionsDirectory);
  }

  private boolean validUnjournaledEntry(Path path, Path dataRelative) {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      return validRelativeTarget(dataRelative);
    }
    String collection = dataRelative.getName(0).toString();
    if (Set.of("menus", "panels", "images").contains(collection)) {
      return true;
    }
    return dataRelative.getNameCount() == 1
        && Set.of("previews", "holograms", "animations", "boards", "emoji", "bubbles",
        "real-drops", "damage-indicators").contains(collection);
  }

  private Journal readJournal(Path transaction) throws IOException {
    requireChild(transactionsDirectory, transaction);
    Path journalPath = transaction.resolve("journal.json").normalize();
    requireChild(transaction, journalPath);
    byte[] journalBytes = readRegularFile(journalPath);
    if (journalBytes.length > MAX_JOURNAL_BYTES) {
      throw new IOException("editor sync transaction journal exceeds the size limit");
    }
    JsonElement parsed = JsonParser.parseString(
        new String(journalBytes, StandardCharsets.UTF_8));
    if (!parsed.isJsonObject()) {
      throw new IOException("editor sync transaction journal must be a JSON object");
    }
    try {
      JsonObject object = parsed.getAsJsonObject();
      if (!object.keySet().equals(Set.of("version", "id", "state", "entries"))) {
        throw new IllegalArgumentException("journal contains unsupported fields");
      }
      int version = object.get("version").getAsInt();
      String id = object.get("id").getAsString();
      String state = object.get("state").getAsString();
      if (version != JOURNAL_VERSION || !id.equals(transaction.getFileName().toString())
          || !List.of("prepared", "publishing", "published", "committed", "rolledback").contains(state)) {
        throw new IllegalArgumentException("invalid journal identity or state");
      }
      List<Entry> entries = new ArrayList<>();
      java.util.Set<String> targetPaths = new java.util.HashSet<>();
      JsonArray array = object.getAsJsonArray("entries");
      for (JsonElement value : array) {
        JsonObject entry = value.getAsJsonObject();
        if (!entry.keySet().equals(Set.of("relativePath", "operation", "existed",
            "originalHash", "stagedHash"))) {
          throw new IllegalArgumentException("journal mutation contains unsupported fields");
        }
        String relative = entry.get("relativePath").getAsString();
        if (!targetPaths.add(relative)) {
          throw new IllegalArgumentException("duplicate journal target path");
        }
        Path normalized = Path.of(relative).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")
            || !relativePath(normalized).equals(relative)) {
          throw new IllegalArgumentException("invalid journal target path");
        }
        Operation operation = Operation.valueOf(entry.get("operation").getAsString());
        boolean existed = entry.get("existed").getAsBoolean();
        String originalHash = entry.has("originalHash") && !entry.get("originalHash").isJsonNull()
            ? entry.get("originalHash").getAsString()
            : null;
        String stagedHash = entry.get("stagedHash").isJsonNull()
            ? null
            : entry.get("stagedHash").getAsString();
        if ((existed && !validHash(originalHash))
            || (!existed && originalHash != null)
            || (operation == Operation.WRITE && !validHash(stagedHash))
            || (operation == Operation.DELETE && (!existed || stagedHash != null))) {
          throw new IllegalArgumentException("invalid journal content hash");
        }
        entries.add(new Entry(relative, operation, existed, originalHash, stagedHash));
      }
      if (entries.isEmpty()) {
        throw new IllegalArgumentException("transaction journal has no entries");
      }
      return new Journal(version, id, state, List.copyOf(entries));
    } catch (RuntimeException failure) {
      throw new IOException("invalid editor sync transaction journal: " + transaction, failure);
    }
  }

  private void writeJournal(Path transaction, Journal journal) throws IOException {
    JsonObject object = new JsonObject();
    object.addProperty("version", journal.version());
    object.addProperty("id", journal.id());
    object.addProperty("state", journal.state());
    JsonArray entries = new JsonArray();
    for (Entry entry : journal.entries()) {
      JsonObject value = new JsonObject();
      value.addProperty("relativePath", entry.relativePath());
      value.addProperty("operation", entry.operation().name());
      value.addProperty("existed", entry.existed());
      if (entry.originalHash() == null) {
        value.add("originalHash", com.google.gson.JsonNull.INSTANCE);
      } else {
        value.addProperty("originalHash", entry.originalHash());
      }
      if (entry.stagedHash() == null) {
        value.add("stagedHash", com.google.gson.JsonNull.INSTANCE);
      } else {
        value.addProperty("stagedHash", entry.stagedHash());
      }
      entries.add(value);
    }
    object.add("entries", entries);
    replaceDurably(transaction.resolve("journal.json"),
        (GSON.toJson(object) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
  }

  private void ensureRootDirectories() throws IOException {
    if (Files.exists(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
      requireDirectory(dataDirectory);
    } else {
      Files.createDirectories(dataDirectory);
      requireDirectory(dataDirectory);
    }
    ensureChildDirectory(dataDirectory, transactionsDirectory);
    ensureChildDirectory(dataDirectory, backupsDirectory);
  }

  private void ensureChildDirectory(Path trustedRoot, Path directory) throws IOException {
    requireChild(trustedRoot, directory);
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      requireDirectory(directory);
      return;
    }
    safePrepareParent(trustedRoot, directory);
    Files.createDirectory(directory);
    forceDirectory(directory.getParent());
  }

  private void createChildDirectory(Path trustedRoot, Path directory) throws IOException {
    requireChild(trustedRoot, directory);
    safePrepareParent(trustedRoot, directory);
    Files.createDirectory(directory);
    forceDirectory(directory.getParent());
  }

  private void safePrepareParent(Path trustedRoot, Path target) throws IOException {
    requireChildOrEqual(trustedRoot, target);
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("path has no parent directory: " + target);
    }
    Path current = trustedRoot;
    requireDirectory(current);
    for (Path segment : trustedRoot.relativize(parent)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        requireDirectory(current);
      } else {
        Files.createDirectory(current);
        forceDirectory(current.getParent());
      }
    }
  }

  private List<Path> childDirectories(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    requireDirectory(root);
    try (Stream<Path> stream = Files.list(root)) {
      List<Path> children = stream.sorted(Comparator.comparing(Path::toString)).toList();
      for (Path child : children) {
        requireChild(root, child);
        requireDirectory(child);
      }
      return new ArrayList<>(children);
    }
  }

  private void validateTarget(Path target) throws IOException {
    requireChild(dataDirectory, target);
    Path relative = dataDirectory.relativize(target);
    if (!validRelativeTarget(relative)) {
      throw new IOException("editor sync target uses an unsupported data collection");
    }
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("editor sync target has no parent directory");
    }
    Path current = dataDirectory;
    for (Path segment : dataDirectory.relativize(parent)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        requireDirectory(current);
      }
    }
    if (Files.isSymbolicLink(target)) {
      throw new IOException("editor sync target must not be a symbolic link");
    }
  }

  private boolean validRelativeTarget(Path relative) {
    if (relative == null || relative.isAbsolute() || relative.startsWith("..")) {
      return false;
    }
    String normalized = relativePath(relative);
    if (normalized.equals("motd.json") || normalized.equals("tablist.json")) {
      return true;
    }
    if (relative.getNameCount() < 2) {
      return false;
    }
    String collection = relative.getName(0).toString();
    if (collection.equals("images")) {
      return true;
    }
    if (collection.equals("menus") || collection.equals(PanelRepository.DIRECTORY_NAME)) {
      return normalized.endsWith(".json");
    }
    if (collection.equals("real-drops")) {
      return normalized.equals("real-drops/default.json");
    }
    if (collection.equals("damage-indicators")) {
      return normalized.equals("damage-indicators/default.json");
    }
    return List.of("previews", "holograms", "animations", "boards", "emoji", "bubbles")
        .contains(collection) && relative.getNameCount() == 2 && normalized.endsWith(".json");
  }

  private byte[] readRegularFile(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("expected a regular non-symbolic file: " + file);
    }
    return Files.readAllBytes(file);
  }

  private void writeNewFile(Path target, byte[] content) throws IOException {
    try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)) {
      write(channel, content);
    }
    forceDirectory(target.getParent());
  }

  private void replaceDurably(Path target, byte[] content) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("replacement target has no parent directory");
    }
    Path trustedRoot = target.startsWith(dataDirectory) ? dataDirectory : transactionsDirectory;
    safePrepareParent(trustedRoot, target);
    Path temporary = AtomicFiles.writeDurableTemporary(parent, ".gloss-sync-", content);
    boolean moved = false;
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      moved = true;
      forceDirectoryAfterReplace(parent, target);
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private void forceDirectoryAfterReplace(Path directory, Path target) throws IOException {
    IOException failure = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        forceDirectory(directory);
        return;
      } catch (IOException attemptFailure) {
        if (failure == null) {
          failure = attemptFailure;
        } else {
          failure.addSuppressed(attemptFailure);
        }
      }
    }
    throw new AtomicReplaceDurabilityException(
        "atomic replacement is visible but directory durability is uncertain: " + target,
        Objects.requireNonNull(failure));
  }

  private void write(FileChannel channel, byte[] content) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(content);
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
    channel.force(true);
  }

  private void forceDirectory(Path directory) throws IOException {
    if (directory == null) {
      return;
    }
    directoryForceProbe.beforeForce(directory);
    AtomicFiles.forceDirectory(directory);
  }

  private void forceFile(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("expected a real file while publishing: " + file);
    }
    AtomicFiles.forceFile(file);
  }

  private void requireDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("expected a real directory: " + directory);
    }
  }

  private void requireChild(Path root, Path child) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedChild = child.toAbsolutePath().normalize();
    if (normalizedChild.equals(normalizedRoot) || !normalizedChild.startsWith(normalizedRoot)) {
      throw new IOException("path escapes trusted directory: " + child);
    }
  }

  private void requireChildOrEqual(Path root, Path child) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedChild = child.toAbsolutePath().normalize();
    if (!normalizedChild.startsWith(normalizedRoot)) {
      throw new IOException("path escapes trusted directory: " + child);
    }
  }

  private Path target(Entry entry) throws IOException {
    Path target = dataDirectory.resolve(entry.relativePath()).normalize();
    validateTarget(target);
    return target;
  }

  /**
   * Drops every empty directory below {@code root} before it is archived.
   *
   * <p>A commit publishes by moving each staged file out of {@code stage/} one at a time, which
   * takes the files but leaves the directory skeleton that held them. Archiving that put an empty
   * {@code stage/}, and every subdirectory of it, in the data folder for as long as the backup was
   * kept. Only directories that are actually empty go: a rolled-back transaction still has its
   * staged files and its {@code backup/} copies, and those are untouched.
   */
  private void pruneEmptyDirectories(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : ordered) {
        if (path.equals(root) || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        try (Stream<Path> children = Files.list(path)) {
          if (children.findAny().isPresent()) {
            continue;
          }
        }
        Files.delete(path);
      }
    }
  }

  private void deleteTree(Path root) throws IOException {
    requireChild(backupsDirectory, root);
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : ordered) {
        if (Files.isSymbolicLink(path)) {
          throw new IOException("refusing to remove symbolic link from editor sync backup: " + path);
        }
        Files.delete(path);
      }
    }
  }

  private static boolean validHash(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static String relativePath(Path path) {
    return path.toString().replace(java.io.File.separatorChar, '/');
  }

  private static String safeLabel(String label) {
    String normalized = label == null ? "sync" : label.replaceAll("[^A-Za-z0-9_-]", "_");
    return normalized.isBlank() ? "sync" : normalized.substring(0, Math.min(normalized.length(), 64));
  }

  public record Pending(Path transactionDirectory, String id, List<Path> publishedFiles) {
    public Pending {
      transactionDirectory = Objects.requireNonNull(transactionDirectory, "transactionDirectory");
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("transaction id must not be blank");
      }
      publishedFiles = List.copyOf(publishedFiles);
    }
  }

  public enum Operation {
    WRITE,
    DELETE
  }

  public record Mutation(Operation operation, byte[] content) {
    public Mutation {
      operation = Objects.requireNonNull(operation, "operation");
      if (operation == Operation.WRITE) {
        content = Objects.requireNonNull(content, "content").clone();
      } else if (content != null) {
        throw new IllegalArgumentException("delete mutation cannot carry content");
      }
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }

    public static Mutation write(byte[] content) {
      return new Mutation(Operation.WRITE, content);
    }

    public static Mutation delete() {
      return new Mutation(Operation.DELETE, null);
    }
  }

  public static final class CommittedCleanupException extends IOException {
    private CommittedCleanupException(String message, IOException cause) {
      super(message, cause);
    }
  }

  public static final class CommitUncertainException extends IOException {
    private CommitUncertainException(String message, IOException cause) {
      super(message, cause);
    }
  }

  @FunctionalInterface
  interface DirectoryForceProbe {
    void beforeForce(Path directory) throws IOException;
  }

  private static final class AtomicReplaceDurabilityException extends IOException {
    private AtomicReplaceDurabilityException(String message, IOException cause) {
      super(message, cause);
    }
  }

  private record Entry(String relativePath, Operation operation, boolean existed,
                       String originalHash, String stagedHash) {
  }

  private record Journal(int version, String id, String state, List<Entry> entries) {
    private Journal withState(String changedState) {
      return new Journal(version, id, changedState, entries);
    }
  }
}
