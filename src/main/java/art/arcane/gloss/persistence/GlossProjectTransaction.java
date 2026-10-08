package art.arcane.gloss.persistence;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
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
  private static final int MAX_RETENTION_ENTRIES = 1048576;
  private static final int MAX_RETENTION_DEPTH = 128;
  private static final int UUID_CHARACTERS = 36;
  private static final String IMAGES_COLLECTION = "images";
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
  private volatile RetentionSettings retentionSettings;
  private volatile RetentionSettings completedRetention;
  private volatile RetentionStatus retentionStatus = new RetentionStatus(0, 0, 0, 0, false, false, false);

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
  }

  public void configureRetention(RetentionPolicy policy) {
    retentionSettings = new RetentionSettings(Objects.requireNonNull(policy, "policy"));
  }

  public boolean retentionPending() {
    return retentionSettings != null && retentionSettings != completedRetention;
  }

  public RetentionStatus retentionStatus() {
    return retentionStatus;
  }

  public void pruneRetainedBackups() throws IOException {
    pruneBackups();
  }

  public Pending apply(String transactionId, Map<Path, Mutation> requestedMutations,
                       Map<Path, byte[]> expectedExisting) throws IOException {
    return apply(transactionId, requestedMutations, expectedExisting,
        new TransactionPreparation(new TransactionPreparation.Limits(Long.MAX_VALUE, Long.MAX_VALUE)));
  }

  public Pending apply(String transactionId, Map<Path, Mutation> requestedMutations,
                       Map<Path, byte[]> expectedExisting, TransactionPreparation preparation) throws IOException {
    Objects.requireNonNull(preparation).check();
    ensureRootDirectories();
    String label = safeLabel(transactionId);
    String id = Instant.now().toEpochMilli() + "-" + label + "-" + UUID.randomUUID();
    LinkedHashMap<Path, Mutation> mutations = orderedMutations(requestedMutations);
    if (mutations.isEmpty()) {
      throw new IllegalArgumentException("editor sync transaction has no mutations");
    }
    List<Entry> capacityEntries = new ArrayList<>(mutations.size());
    String hashCapacity = "0".repeat(64);
    long preparationBytes = 0;
    for (Map.Entry<Path, Mutation> mutation : mutations.entrySet()) {
      preparation.check();
      validateTarget(mutation.getKey());
      if (mutation.getValue().operation() == Operation.WRITE) {
        preparationBytes = Math.addExact(preparationBytes, mutation.getValue().contentLength());
      }
      if (Files.exists(mutation.getKey(), LinkOption.NOFOLLOW_LINKS)) {
        preparationBytes = Math.addExact(preparationBytes, Files.size(mutation.getKey()));
      }
      preparation.requireCapacity(preparationBytes);
      capacityEntries.add(new Entry(relativePath(dataDirectory.relativize(mutation.getKey())),
          mutation.getValue().operation(), false, hashCapacity,
          mutation.getValue().operation() == Operation.WRITE ? hashCapacity : null));
    }
    journalBytes(new Journal(JOURNAL_VERSION, id, "publishing", capacityEntries));
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
        preparation.check();
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
          preparation.reserve(mutation.contentLength());
          writeNewFile(staged, mutation.content, preparation);
          stagedHash = DocumentHashes.sha256(mutation.content);
        }
        String originalHash = null;
        if (existed) {
          ensureChildDirectory(transaction, backup);
          Path backupFile = backup.resolve(relative).normalize();
          safePrepareParent(backup, backupFile);
          originalHash = copyRegularFile(target, backupFile, preparation);
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
      verifyExpected(expectedExisting, preparation);
      preparation.check();
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

  private void verifyExpected(Map<Path, byte[]> expectedExisting, TransactionPreparation preparation) throws IOException {
    for (Map.Entry<Path, byte[]> entry : expectedExisting.entrySet()) {
      preparation.check();
      Path target = entry.getKey().toAbsolutePath().normalize();
      validateTarget(target);
      byte[] expected = entry.getValue();
      if (expected == null) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("transaction target was created after the session snapshot: " + target);
        }
      } else if (!matchesExpected(target, expected, preparation)) {
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
            if (!hashRegularFile(stagedFile).equals(entry.stagedHash())) {
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
          if (!hashRegularFile(backupFile).equals(entry.originalHash())) {
            throw new IOException("editor sync backup hash mismatch: " + entry.relativePath());
          }
          if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (entry.operation() == Operation.DELETE) {
              restoreDurably(target, backupFile, entry.originalHash());
              continue;
            }
            throw new IOException("editor sync target disappeared during recovery: "
                + entry.relativePath());
          }
          String targetHash = hashRegularFile(target);
          if (targetHash.equals(entry.originalHash())) {
            continue;
          }
          if (entry.operation() == Operation.DELETE || !targetHash.equals(entry.stagedHash())) {
            throw new IOException("editor sync target changed independently during recovery: "
                + entry.relativePath());
          }
          restoreDurably(target, backupFile, entry.originalHash());
        } else {
          if (entry.operation() != Operation.WRITE) {
            throw new IOException("delete mutation has no original file: " + entry.relativePath());
          }
          validateTarget(target);
          if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            String targetHash = hashRegularFile(target);
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
    RetentionSettings settings = retentionSettings;
    if (settings == null) {
      return;
    }
    try {
      pruneBackups(settings);
    } catch (IOException | ArithmeticException failure) {
      RetentionStatus previous = retentionStatus;
      retentionStatus = new RetentionStatus(previous.retainedBackups(), previous.retainedBytes(),
          previous.protectedBackups(), previous.protectedBytes(), previous.overBudget(),
          previous.recoveryBlocked(), false);
      if (failure instanceof IOException io) {
        throw io;
      }
      throw new IOException("transaction retention byte accounting overflowed", failure);
    } finally {
      completedRetention = settings;
    }
  }

  private void pruneBackups(RetentionSettings settings) throws IOException {
    RetentionPolicy policy = settings.policy();
    if (!Files.exists(backupsDirectory, LinkOption.NOFOLLOW_LINKS)) {
      retentionStatus = new RetentionStatus(0, 0, 0, 0, false, false, true);
      return;
    }
    requireDirectory(backupsDirectory);
    ScanBudget budget = new ScanBudget();
    List<RetainedArchive> archives = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(backupsDirectory)) {
      for (Path child : children) {
        budget.visit();
        archives.add(inspectArchive(child, budget));
      }
    }
    archives.sort(Comparator.comparing(RetainedArchive::completedAt)
        .thenComparing(archive -> archive.path().getFileName().toString()));
    boolean recoveryBlocked = !uncertainCommits.isEmpty() || unresolvedTransactions();
    long bytes = 0;
    for (RetainedArchive archive : archives) {
      bytes = Math.addExact(bytes, archive.bytes());
    }
    boolean pruningRequired = archives.size() > policy.maxBackups() || bytes > policy.maxBytes();
    RetainedArchive newest = null;
    if (!recoveryBlocked) {
      newest = newestArchive(archives, true, pruningRequired);
      if (newest == null) {
        newest = newestArchive(archives, false, pruningRequired);
      }
    }
    long protectedBytes = 0;
    int protectedCount = 0;
    for (RetainedArchive archive : archives) {
      if (recoveryBlocked || !archive.terminal() || archive == newest) {
        protectedCount++;
        protectedBytes = Math.addExact(protectedBytes, archive.bytes());
      }
    }
    int count = archives.size();
    retentionStatus = retentionStatus(policy, count, bytes, protectedCount, protectedBytes, recoveryBlocked, true);
    if (recoveryBlocked) {
      return;
    }
    for (RetainedArchive archive : archives) {
      if (retentionSettings != settings || count <= policy.maxBackups() && bytes <= policy.maxBytes()) {
        break;
      }
      if (!archive.terminal() || archive == newest) {
        continue;
      }
      try {
        deleteRetainedArchive(archive);
        count--;
        bytes -= archive.bytes();
        forceDirectory(backupsDirectory);
      } catch (IOException failure) {
        retentionStatus = retentionStatus(policy, count, bytes, protectedCount, protectedBytes, false, false);
        throw failure;
      }
      retentionStatus = retentionStatus(policy, count, bytes, protectedCount, protectedBytes, false, true);
    }
  }

  private RetainedArchive newestArchive(List<RetainedArchive> archives, boolean committedOriginals,
                                        boolean verifyContents) {
    for (int index = archives.size() - 1; index >= 0; index--) {
      RetainedArchive archive = archives.get(index);
      if (!archive.terminal() || committedOriginals && !archive.committedOriginals()) {
        continue;
      }
      try {
        if (verifyContents) {
          verifyArchiveOriginals(archive);
        }
        return archive;
      } catch (IOException failure) {
        archives.set(index, new RetainedArchive(archive.path(), archive.bytes(), false,
            archive.committedOriginals(), archive.fileKey(), archive.completedAt(), archive.journal()));
        Gloss.logExceptionStack(false, failure, "Transaction retention preserved a damaged rollback archive: %s", archive.path());
      }
    }
    return null;
  }

  private void verifyArchiveOriginals(RetainedArchive archive) throws IOException {
    for (Entry entry : archive.journal().entries()) {
      if (!entry.existed()) {
        continue;
      }
      Path original = archive.path().resolve("backup").resolve(entry.relativePath()).normalize();
      requireChild(archive.path(), original);
      Path parent = archive.path();
      requireDirectory(parent);
      for (Path segment : archive.path().relativize(original.getParent())) {
        parent = parent.resolve(segment);
        requireDirectory(parent);
      }
      if (!hashRegularFile(original).equals(entry.originalHash())) {
        throw new IOException("transaction archive original hash mismatch: " + original);
      }
    }
  }

  private RetainedArchive inspectArchive(Path path, ScanBudget budget) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    ArchiveInventory inventory = new ArchiveInventory(budget);
    if (attributes.isDirectory()) {
      Files.walkFileTree(path, Set.of(), MAX_RETENTION_DEPTH, inventory);
    } else {
      inventory.visitFile(path, attributes);
      inventory.safe = false;
    }
    boolean terminal = false;
    boolean committedOriginals = false;
    Journal verifiedJournal = null;
    FileTime completedAt = attributes.lastModifiedTime();
    if (inventory.safe && TRANSACTION_DIRECTORY.matcher(path.getFileName().toString()).matches()) {
      try {
        Journal journal = readJournal(backupsDirectory, path);
        verifiedJournal = journal;
        completedAt = Files.getLastModifiedTime(path.resolve("journal.json"), LinkOption.NOFOLLOW_LINKS);
        terminal = journal.state().equals("committed") || journal.state().equals("rolledback");
        for (Entry entry : journal.entries()) {
          if (entry.existed()) {
            terminal &= Files.isRegularFile(path.resolve("backup").resolve(entry.relativePath()), LinkOption.NOFOLLOW_LINKS);
            committedOriginals |= journal.state().equals("committed");
          }
        }
      } catch (IOException | RuntimeException invalid) {
        Gloss.logExceptionStack(false, invalid, "Transaction retention preserved an unreadable archive: %s", path);
      }
    }
    return new RetainedArchive(path, inventory.bytes, terminal, committedOriginals, attributes.fileKey(),
        completedAt, verifiedJournal);
  }

  private boolean unresolvedTransactions() throws IOException {
    if (!Files.exists(transactionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    requireDirectory(transactionsDirectory);
    try (DirectoryStream<Path> transactions = Files.newDirectoryStream(transactionsDirectory)) {
      return transactions.iterator().hasNext();
    }
  }

  private void deleteRetainedArchive(RetainedArchive archive) throws IOException {
    try (DirectoryStream<Path> directory = Files.newDirectoryStream(backupsDirectory)) {
      if (!(directory instanceof SecureDirectoryStream<Path> secure)) {
        throw new IOException("transaction retention requires secure directory deletion support");
      }
      Path name = archive.path().getFileName();
      BasicFileAttributes current = secure.getFileAttributeView(name, BasicFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS).readAttributes();
      if (!current.isDirectory() || archive.fileKey() == null || !archive.fileKey().equals(current.fileKey())) {
        throw new IOException("transaction archive changed during retention: " + archive.path());
      }
      if (!Objects.equals(archive.journal(), readJournal(backupsDirectory, archive.path()))) {
        throw new IOException("transaction archive journal changed during retention: " + archive.path());
      }
      try (SecureDirectoryStream<Path> child = secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
        BasicFileAttributes opened = child.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
        if (!archive.fileKey().equals(opened.fileKey())) {
          throw new IOException("transaction archive changed while opening retention directory: " + archive.path());
        }
        deleteRetainedContents(child, new ScanBudget(), 0);
      }
      BasicFileAttributes remaining = secure.getFileAttributeView(name, BasicFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS).readAttributes();
      if (!archive.fileKey().equals(remaining.fileKey())) {
        throw new IOException("transaction archive changed before retention directory removal: " + archive.path());
      }
      secure.deleteDirectory(name);
    }
  }

  private void deleteRetainedContents(SecureDirectoryStream<Path> directory, ScanBudget budget, int depth)
      throws IOException {
    if (depth >= MAX_RETENTION_DEPTH) {
      throw new IOException("transaction retention exceeds directory depth limit");
    }
    List<Path> files = new ArrayList<>();
    for (Path child : directory) {
      budget.visit();
      files.add(child.getFileName());
    }
    files.sort(Comparator.comparing(path -> path.toString().equals("journal.json")));
    for (Path name : files) {
      BasicFileAttributes attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS).readAttributes();
      if (attributes.isSymbolicLink() || !attributes.isDirectory() && !attributes.isRegularFile()) {
        throw new IOException("transaction archive contains an unsafe entry: " + name);
      }
      if (attributes.isDirectory()) {
        try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
          deleteRetainedContents(child, budget, depth + 1);
        }
        directory.deleteDirectory(name);
      } else {
        directory.deleteFile(name);
      }
    }
  }

  private static RetentionStatus retentionStatus(RetentionPolicy policy, int count, long bytes,
                                                 int protectedCount, long protectedBytes,
                                                 boolean recoveryBlocked, boolean complete) {
    return new RetentionStatus(count, bytes, protectedCount, protectedBytes,
        count > policy.maxBackups() || bytes > policy.maxBytes(), recoveryBlocked, complete);
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
    if (collection.equals(IMAGES_COLLECTION)) {
      return true;
    }
    EditorSyncDocumentKind kind = EditorSyncDocumentKind.byStorageCollection(collection);
    if (kind == null || kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
      return false;
    }
    return kind.layout() == EditorSyncDocumentKind.Layout.TREE || dataRelative.getNameCount() == 1;
  }

  private Journal readJournal(Path transaction) throws IOException {
    return readJournal(transactionsDirectory, transaction);
  }

  private Journal readJournal(Path root, Path transaction) throws IOException {
    requireChild(root, transaction);
    Path journalPath = transaction.resolve("journal.json").normalize();
    requireChild(transaction, journalPath);
    byte[] journalBytes;
    try (FileChannel channel = openRegularFile(journalPath)) {
      if (channel.size() > MAX_JOURNAL_BYTES) {
        throw new IOException("editor sync transaction journal exceeds the size limit");
      }
      journalBytes = Channels.newInputStream(channel).readNBytes((int) MAX_JOURNAL_BYTES + 1);
    }
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
    replaceDurably(transaction.resolve("journal.json"), journalBytes(journal));
  }

  private byte[] journalBytes(Journal journal) throws IOException {
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
    byte[] content = (GSON.toJson(object) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    if (content.length > MAX_JOURNAL_BYTES) {
      throw new IOException("editor sync transaction journal exceeds the size limit");
    }
    return content;
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
    String collection = relative.getName(0).toString();
    if (relative.getNameCount() == 1) {
      if (Set.of("gloss.toml", "legacy-import.json", "holoui-import.json", "preview-scales.json").contains(collection)) {
        return true;
      }
      EditorSyncDocumentKind single = EditorSyncDocumentKind.byStorageCollection(collection);
      return single != null && single.layout() == EditorSyncDocumentKind.Layout.SINGLE;
    }
    if (collection.equals(IMAGES_COLLECTION)) {
      return true;
    }
    EditorSyncDocumentKind kind = EditorSyncDocumentKind.byStorageCollection(collection);
    if (kind == null || kind.layout() == EditorSyncDocumentKind.Layout.SINGLE || !normalized.endsWith(".json")) {
      return false;
    }
    if (kind.layout() == EditorSyncDocumentKind.Layout.TREE) {
      return true;
    }
    if (kind.singletonId() != null) {
      return normalized.equals(kind.storageName() + "/" + kind.singletonId() + ".json");
    }
    return relative.getNameCount() == 2;
  }

  private FileChannel openRegularFile(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("expected a regular non-symbolic file: " + file);
    }
    return FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  }

  private boolean matchesExpected(Path file, byte[] expected, TransactionPreparation preparation) throws IOException {
    try (FileChannel channel = openRegularFile(file)) {
      if (channel.size() != expected.length) {
        return false;
      }
      InputStream input = Channels.newInputStream(channel);
      byte[] buffer = new byte[8192];
      int offset = 0;
      while (offset < expected.length) {
        preparation.check();
        int count = input.read(buffer, 0, Math.min(buffer.length, expected.length - offset));
        if (count < 0 || !Arrays.equals(expected, offset, offset + count, buffer, 0, count)) {
          return false;
        }
        offset += count;
      }
      return input.read() == -1;
    }
  }

  private String hashRegularFile(Path file) throws IOException {
    return transferRegularFile(file, null);
  }

  private String copyRegularFile(Path source, Path target) throws IOException {
    return copyRegularFile(source, target, null);
  }

  private String copyRegularFile(Path source, Path target, TransactionPreparation preparation) throws IOException {
    String hash;
    try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      hash = transferRegularFile(source, output, preparation);
      output.force(true);
    }
    forceDirectory(target.getParent());
    return hash;
  }

  private String transferRegularFile(Path source, FileChannel output) throws IOException {
    return transferRegularFile(source, output, null);
  }

  private String transferRegularFile(Path source, FileChannel output, TransactionPreparation preparation) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
    try (FileChannel input = openRegularFile(source)) {
      long remaining = input.size();
      if (preparation != null) {
        preparation.reserve(remaining);
      }
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      while (remaining > 0) {
        if (preparation != null) {
          preparation.check();
        }
        buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
        int count = input.read(buffer);
        if (count < 0) {
          throw new IOException("transaction file changed while reading: " + source);
        }
        remaining -= count;
        digest.update(buffer.array(), 0, count);
        if (output != null) {
          buffer.flip();
          while (buffer.hasRemaining()) {
            output.write(buffer);
          }
        }
      }
      buffer.clear().limit(1);
      if (input.read(buffer) != -1) {
        throw new IOException("transaction file changed while reading: " + source);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private void restoreDurably(Path target, Path backup, String expectedHash) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("replacement target has no parent directory");
    }
    safePrepareParent(dataDirectory, target);
    Path temporary = parent.resolve(".gloss-sync-" + UUID.randomUUID() + ".tmp");
    boolean moved = false;
    try {
      if (!copyRegularFile(backup, temporary).equals(expectedHash)) {
        throw new IOException("editor sync backup hash mismatch: " + backup);
      }
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

  private void writeNewFile(Path target, byte[] content, TransactionPreparation preparation) throws IOException {
    try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        preparation.check();
        buffer.limit(Math.min(content.length, buffer.position() + Math.min(8192, buffer.remaining())));
        channel.write(buffer);
        buffer.limit(content.length);
      }
      preparation.check();
      channel.force(true);
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

  public record RetentionPolicy(int maxBackups, long maxBytes) {
    public RetentionPolicy {
      if (maxBackups < 1 || maxBytes < 1) {
        throw new IllegalArgumentException("Transaction retention limits must be positive");
      }
    }
  }

  public record RetentionStatus(int retainedBackups, long retainedBytes, int protectedBackups,
                                long protectedBytes, boolean overBudget, boolean recoveryBlocked,
                                boolean inventoryComplete) {
  }

  private record RetentionSettings(RetentionPolicy policy) {
  }

  private record RetainedArchive(Path path, long bytes, boolean terminal, boolean committedOriginals,
                                 Object fileKey, FileTime completedAt, Journal journal) {
  }

  private static final class ScanBudget {
    private int visited;

    private void visit() throws IOException {
      if (++visited > MAX_RETENTION_ENTRIES || Thread.currentThread().isInterrupted()) {
        throw new IOException("transaction retention inventory exceeded its entry limit or was interrupted");
      }
    }
  }

  private static final class ArchiveInventory extends SimpleFileVisitor<Path> {
    private final ScanBudget budget;
    private long bytes;
    private boolean safe = true;

    private ArchiveInventory(ScanBudget budget) {
      this.budget = budget;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
      budget.visit();
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
      budget.visit();
      if (attributes.isDirectory()) {
        throw new IOException("transaction retention inventory exceeded its directory depth limit");
      }
      if (attributes.isRegularFile()) {
        bytes = Math.addExact(bytes, attributes.size());
      } else {
        safe = false;
      }
      return FileVisitResult.CONTINUE;
    }
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

    public int contentLength() {
      return content == null ? 0 : content.length;
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

  /**
   * Every archived publication whose journal committed, newest last, with the original bytes each
   * one replaced. The history timeline reads these so an editor publication appears beside the
   * copies the watchdog and the pack installer took.
   */
  public List<TransactionRecord> committedTransactions() throws IOException {
    if (!Files.isDirectory(backupsDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<TransactionRecord> records = new ArrayList<>();
    try (Stream<Path> stream = Files.list(backupsDirectory)) {
      for (Path backup : stream.sorted(Comparator.comparing(Path::getFileName)).toList()) {
        String id = backup.getFileName().toString();
        if (!Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)
            || !TRANSACTION_DIRECTORY.matcher(id).matches()) {
          continue;
        }
        Journal journal = readJournal(backupsDirectory, backup);
        if (!journal.state().equals("committed")) {
          continue;
        }
        records.add(transactionRecord(backup, id, journal));
      }
    }
    return List.copyOf(records);
  }

  private TransactionRecord transactionRecord(Path backup, String id, Journal journal)
      throws IOException {
    int firstSeparator = id.indexOf('-');
    long epochMillis = Long.parseLong(id.substring(0, firstSeparator));
    String label = id.substring(firstSeparator + 1, id.length() - UUID_CHARACTERS - 1);
    List<TransactionFile> files = new ArrayList<>();
    Path originals = backup.resolve("backup");
    for (Entry entry : journal.entries()) {
      if (!entry.existed()) {
        continue;
      }
      Path original = originals.resolve(entry.relativePath()).normalize();
      if (!original.startsWith(originals)
          || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      files.add(new TransactionFile(entry.relativePath(), original, Files.size(original)));
    }
    return new TransactionRecord(id, epochMillis, label, List.copyOf(files));
  }

  /** One archived publication: its identity, when it ran, the label that requested it, and its backups. */
  public record TransactionRecord(String id, long epochMillis, String label,
                                  List<TransactionFile> files) {
    public TransactionRecord {
      id = Objects.requireNonNull(id, "id");
      label = Objects.requireNonNull(label, "label");
      files = List.copyOf(files);
    }
  }

  /** One data-folder file a publication replaced, and the copy of what it replaced. */
  public record TransactionFile(String relativePath, Path backupFile, long bytes) {
    public TransactionFile {
      relativePath = Objects.requireNonNull(relativePath, "relativePath");
      backupFile = Objects.requireNonNull(backupFile, "backupFile");
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
