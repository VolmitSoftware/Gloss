package art.arcane.gloss.editor.sync;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.CommandActions;
import art.arcane.gloss.editor.EditorScope;
import art.arcane.gloss.history.HistoryEntry;
import art.arcane.gloss.history.HistoryService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The workspace as the editor API serves it: one catalog row per document and image (hash, size, summary), the
 * bodies and image bytes behind them, the meta and notes sections, and publishing. {@link #refresh} reads the
 * files again (only those whose size or time moved) and reports what changed; {@link #publish} merges a changeset
 * with per-entry base hashes, validates it and writes it in one transaction. Call both, and {@link #moved}, on the
 * editor's file lane. {@link #view} and {@link #read} never wait and may be called from any thread;
 * {@link #settleNotes} builds the notes outside the lock, so a slow build holds up neither.
 */
public final class EditorWorkspace {
  private static final int NAME_LIMIT = 80;
  private static final int CONFLICT_NAMES_SHOWN = 5;
  public static final int META_VERSION = 1;

  /** Who changed a document, as the {@code source} of its event entry. */
  public enum Source {
    EDITOR("editor"), SERVER("server"), FILE("file");

    private final String wireName;

    Source(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }

  /** One document's catalog row. {@code revision} is null for kinds without one. */
  public record DocumentRow(String kind, String id, String path, String hash, int size, Long revision, String name,
                            boolean commands) {
    public String folder() {
      int slash = id.lastIndexOf('/');
      return slash < 0 ? "" : id.substring(0, slash);
    }

    public JsonObject json() {
      JsonObject row = new JsonObject();
      row.addProperty("kind", kind);
      row.addProperty("id", id);
      row.addProperty("path", path);
      row.addProperty("hash", hash);
      row.addProperty("size", size);
      if (revision != null) {
        row.addProperty("revision", revision);
      }
      JsonObject summary = new JsonObject();
      summary.addProperty("name", name);
      if (!folder().isEmpty()) {
        summary.addProperty("folder", folder());
      }
      if (commands) {
        summary.addProperty("commands", true);
      }
      row.add("summary", summary);
      return row;
    }
  }

  /** One image's catalog row. */
  public record ImageRow(String path, String hash, int size, String mediaType, int width, int height) {
    public String folder() {
      int slash = path.lastIndexOf('/');
      return slash < 0 ? "" : path.substring(0, slash);
    }

    public JsonObject json() {
      JsonObject row = new JsonObject();
      row.addProperty("path", path);
      row.addProperty("hash", hash);
      row.addProperty("size", size);
      row.addProperty("mediaType", mediaType);
      row.addProperty("width", width);
      row.addProperty("height", height);
      return row;
    }
  }

  /** Image bytes by hash. */
  public record Blob(String hash, String mediaType, byte[] bytes) {
  }

  /** A removed document and the data-folder path it had. */
  public record DocumentRef(String kind, String id, String path) {
    public JsonObject json() {
      JsonObject ref = new JsonObject();
      ref.addProperty("kind", kind);
      ref.addProperty("id", id);
      return ref;
    }
  }

  /** What one refresh found changed. {@code metaHash}/{@code notesHash} are set only when they moved. */
  public record Delta(List<DocumentRow> documents, List<DocumentRef> deleted, List<ImageRow> images,
                      List<String> removedImages, String metaHash, String notesHash) {
    public Delta {
      documents = List.copyOf(documents);
      deleted = List.copyOf(deleted);
      images = List.copyOf(images);
      removedImages = List.copyOf(removedImages);
    }

    public boolean empty() {
      return documents.isEmpty() && deleted.isEmpty() && images.isEmpty() && removedImages.isEmpty() && metaHash == null
          && notesHash == null;
    }

    public boolean contentChanged() {
      return !documents.isEmpty() || !deleted.isEmpty() || !images.isEmpty() || !removedImages.isEmpty();
    }

    static Delta none() {
      return new Delta(List.of(), List.of(), List.of(), List.of(), null, null);
    }
  }

  /** The catalog as last read: rows sorted by kind then id, images by path. Immutable. */
  public record View(List<DocumentRow> documents, Map<String, String> bodies, List<ImageRow> images,
                     Map<String, Blob> blobs, String metaHash, String notesHash) {
    public Optional<DocumentRow> row(String kind, String id) {
      int index = Collections.binarySearch(documents, new DocumentRow(kind, id, "", "", 0, null, "", false),
          EditorWorkspace::compareRows);
      return index < 0 ? Optional.empty() : Optional.of(documents.get(index));
    }

    public Optional<String> body(String kind, String id) {
      return Optional.ofNullable(bodies.get(EditorSyncChangesetMerger.key(kind, id)));
    }
  }

  /**
   * The result of a publish. {@code delta} holds what changed among the entries the publish named and landed;
   * {@code others} holds every other change the files showed when it read them again, which other writers made.
   */
  public record Outcome(String status, String message, List<EditorSyncConflict> conflicts, Delta delta, Delta others) {
    public Outcome {
      conflicts = List.copyOf(conflicts);
    }
  }

  /**
   * Writes a validated publish; throws when nothing could be applied (the files are as before), or
   * {@link StrandedPublishException} when it failed part way and could not be undone.
   */
  interface Applier {
    void apply(String changeId, EditorSyncProject server, EditorSyncPublicationValidator.ValidatedProject validated,
               Supplier<EditorSyncProject> reread) throws IOException;
  }

  /** A publish that failed after writing and could not be rolled back; the files stay as they were found. */
  static final class StrandedPublishException extends IOException {
    StrandedPublishException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The workspace as a publish judges it: the readable files as one project, and the files it could not read. */
  private record Current(EditorSyncProject project, Set<String> unreadable, Set<String> unreadableImages) {
  }

  /** Reads a stored version from the server's history. */
  interface HistoryReader {
    Optional<String> read(String collection, String id, long version);
  }

  private final EditorSyncContentSnapshotBuilder content;
  private final EditorSyncContentSnapshotBuilder.ServerSections sections;
  private final Applier applier;
  private final HistoryReader history;
  private final IntSupplier maximumBytes;
  private final EditorSyncPublicationValidator validator = new EditorSyncPublicationValidator();
  private volatile View view = new View(List.of(), Map.of(), List.of(), Map.of(), "", "");
  private volatile JsonObject meta;
  private volatile JsonObject notes = new JsonObject();
  private List<String> listingWarnings = List.of();
  private List<EditorSyncDocuments.Entry> entries = List.of();
  private boolean notesDirty = true;
  /** Held for a whole notes build, so builds land in the order their listings were read. */
  private final Object notesBuild = new Object();
  private volatile boolean notesBuilt;
  private volatile boolean read;
  private String projectDigest;
  private EditorSyncProject project;

  EditorWorkspace(EditorSyncContentSnapshotBuilder content, EditorSyncContentSnapshotBuilder.ServerSections sections,
                  Applier applier, HistoryReader history, IntSupplier maximumBytes) {
    this.content = Objects.requireNonNull(content);
    this.sections = Objects.requireNonNull(sections);
    this.applier = Objects.requireNonNull(applier);
    this.history = Objects.requireNonNull(history);
    this.maximumBytes = Objects.requireNonNull(maximumBytes);
  }

  /** The workspace of this Gloss server. */
  public static EditorWorkspace of(Gloss plugin, IntSupplier maximumProjectBytes) {
    EditorSyncSnapshotBuilder builder = new EditorSyncSnapshotBuilder(plugin);
    EditorSyncContentSnapshotBuilder content = new EditorSyncContentSnapshotBuilder(plugin.getDataFolder().toPath());
    EditorSyncContentSnapshotBuilder.ServerSections sections = new EditorSyncContentSnapshotBuilder.ServerSections() {
      @Override
      public List<String> warnings(List<EditorSyncDocuments.Entry> documents) {
        return builder.lintWarnings(documents);
      }

      @Override
      public JsonArray history(List<EditorSyncDocuments.Entry> documents) {
        return builder.history(documents);
      }

      @Override
      public JsonObject schemas() {
        return EditorSyncServerCatalog.schemas();
      }

      @Override
      public JsonObject defaults() {
        return EditorSyncServerCatalog.defaults();
      }
    };
    return new EditorWorkspace(content, sections, new EditorSyncApplier(plugin), (String collection, String id,
        long version) -> readHistory(plugin, collection, id, version), maximumProjectBytes);
  }

  private static Optional<String> readHistory(Gloss plugin, String collection, String id, long version) {
    HistoryService service = plugin.service(HistoryService.class);
    if (service == null) {
      return Optional.empty();
    }
    HistoryEntry entry = service.version(collection, id, version);
    if (entry == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new String(service.store().read(entry), StandardCharsets.UTF_8));
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  /** The catalog as last read. */
  public View view() {
    return view;
  }

  /** True once the files were read once. */
  public boolean read() {
    return read;
  }

  /** True when a workspace file was added, removed or changed since the last refresh; stats files, reads none. */
  public boolean moved() {
    return content.moved();
  }

  /** The meta section (kinds with their scopes, schemas, defaults); built once. */
  public JsonObject meta() {
    JsonObject current = meta;
    if (current == null) {
      current = buildMeta();
      meta = current;
    }
    return current.deepCopy();
  }

  public String metaHash() {
    meta();
    return hash(meta);
  }

  /**
   * The notes section (warnings and stored history) as last built. Only before the first build has landed does this
   * build them, or wait for the build already running.
   */
  public JsonObject notes() {
    if (!notesBuilt) {
      settleNotes();
    }
    return notes.deepCopy();
  }

  /**
   * Reads the files again and returns what changed since the last read. The first read returns everything as
   * changed. Notes are recomputed later by {@link #settleNotes}, which reports their hash moving.
   */
  public synchronized Delta refresh() {
    EditorSyncContentSnapshotBuilder.Listing listing = content.list(true, maximumBytes.getAsInt());
    return refresh(listing);
  }

  /**
   * A file that exists but cannot be read now (a half-written save, a hand edit in progress) keeps its last row, so
   * it is never reported as deleted.
   */
  private Delta refresh(EditorSyncContentSnapshotBuilder.Listing listing) {
    View before = view;
    Map<String, DocumentRow> previous = new LinkedHashMap<>();
    for (DocumentRow row : before.documents()) {
      previous.put(EditorSyncChangesetMerger.key(row.kind(), row.id()), row);
    }
    List<DocumentRow> rows = new ArrayList<>();
    Map<String, String> bodies = new TreeMap<>();
    List<DocumentRow> changed = new ArrayList<>();
    for (EditorSyncDocuments.Entry entry : listing.documents()) {
      String key = EditorSyncChangesetMerger.key(entry.kind(), entry.id());
      DocumentRow old = previous.remove(key);
      DocumentRow row = old != null && old.hash().equals(entry.baseRevision()) ? old : row(entry, listing.paths().get(key));
      rows.add(row);
      bodies.put(key, entry.json());
      if (old == null || !old.hash().equals(row.hash())) {
        changed.add(row);
      }
    }
    List<DocumentRef> deleted = new ArrayList<>();
    for (Map.Entry<String, DocumentRow> gone : previous.entrySet()) {
      DocumentRow row = gone.getValue();
      String body = before.bodies().get(gone.getKey());
      if (listing.unreadable().contains(gone.getKey()) && body != null) {
        rows.add(row);
        bodies.put(gone.getKey(), body);
        continue;
      }
      deleted.add(new DocumentRef(row.kind(), row.id(), row.path()));
    }
    rows.sort(EditorWorkspace::compareRows);
    Map<String, ImageRow> previousImages = new LinkedHashMap<>();
    for (ImageRow image : before.images()) {
      previousImages.put(image.path(), image);
    }
    List<ImageRow> images = new ArrayList<>();
    Map<String, Blob> blobs = new LinkedHashMap<>();
    List<ImageRow> changedImages = new ArrayList<>();
    for (EditorSyncContentSnapshotBuilder.ImageFile file : listing.images()) {
      ImageRow old = previousImages.remove(file.path());
      ImageRow row = old != null && old.hash().equals(file.hash()) ? old : new ImageRow(file.path(), file.hash(),
          file.bytes().length, file.image().mediaType(), file.image().width(), file.image().height());
      images.add(row);
      blobs.putIfAbsent(row.hash(), new Blob(row.hash(), row.mediaType(), file.bytes()));
      if (old == null || !old.hash().equals(row.hash())) {
        changedImages.add(row);
      }
    }
    List<String> removedImages = new ArrayList<>();
    for (ImageRow gone : previousImages.values()) {
      Blob blob = before.blobs().get(gone.hash());
      if (listing.unreadableImages().contains(gone.path()) && blob != null) {
        images.add(gone);
        blobs.putIfAbsent(gone.hash(), blob);
        continue;
      }
      removedImages.add(gone.path());
    }
    images.sort(Comparator.comparing(ImageRow::path));
    boolean warningsMoved = !listing.warnings().equals(listingWarnings);
    listingWarnings = listing.warnings();
    entries = listing.documents();
    read = true;
    Delta delta = new Delta(changed, deleted, changedImages, removedImages, null, null);
    if (delta.contentChanged() || warningsMoved) {
      notesDirty = true;
    }
    view = new View(List.copyOf(rows), Collections.unmodifiableMap(bodies), List.copyOf(images),
        Collections.unmodifiableMap(blobs), before.metaHash().isEmpty() ? metaHash() : before.metaHash(), before.notesHash());
    return delta;
  }

  /**
   * Recomputes the notes when documents changed since; returns their new hash when it moved, else null. Builds run
   * one at a time on the listing each started from, outside the workspace lock, so refreshes and reads go on.
   */
  public String settleNotes() {
    synchronized (notesBuild) {
      List<String> listingNow;
      List<EditorSyncDocuments.Entry> entriesNow;
      synchronized (this) {
        if (!notesDirty) {
          return null;
        }
        notesDirty = false;
        listingNow = listingWarnings;
        entriesNow = entries;
      }
      JsonObject next;
      try {
        next = buildNotes(listingNow, entriesNow);
      } catch (RuntimeException failure) {
        synchronized (this) {
          notesDirty = true;
        }
        throw failure;
      }
      String nextHash = hash(next);
      synchronized (this) {
        notes = next;
        notesBuilt = true;
        View current = view;
        if (nextHash.equals(current.notesHash())) {
          return null;
        }
        view = new View(current.documents(), current.bodies(), current.images(), current.blobs(), current.metaHash(),
            nextHash);
        return nextHash;
      }
    }
  }

  private JsonObject buildNotes(List<String> listingNow, List<EditorSyncDocuments.Entry> entriesNow) {
    List<String> warnings = new ArrayList<>(listingNow);
    warnings.addAll(EditorSyncContentSnapshotBuilder.warnings(entriesNow));
    warnings.addAll(sections.warnings(entriesNow));
    JsonObject next = new JsonObject();
    next.addProperty("format", "gloss-editor-notes");
    next.addProperty("version", META_VERSION);
    JsonArray warningArray = new JsonArray();
    warnings.stream().sorted().limit(EditorSyncSnapshotBuilder.MAX_WARNING_COUNT).forEach(warningArray::add);
    next.add("warnings", warningArray);
    next.add("history", sections.history(entriesNow));
    return next;
  }

  /** A stored version of a document, from the server's history. */
  public Optional<String> history(String collection, String id, long version) {
    return history.read(collection, id, version);
  }

  /** Checks uploaded image bytes as a workspace image: its media type, size and dimensions. */
  public static ImageRow validateUpload(String declaredMediaType, byte[] bytes) {
    if (bytes.length == 0 || bytes.length > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
      throw new IllegalArgumentException("an image is 1 byte to " + EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES + " bytes");
    }
    String detected = EditorSyncSnapshotBuilder.detectMediaType(bytes);
    if (declaredMediaType != null && !declaredMediaType.toLowerCase(Locale.ROOT).equals(detected)) {
      throw new IllegalArgumentException("bytes do not match " + declaredMediaType);
    }
    EditorSyncSnapshotBuilder.ValidatedImage image = EditorSyncSnapshotBuilder.validateWorkspaceImage("upload", bytes);
    return new ImageRow("", sha256(bytes), bytes.length, image.mediaType(), image.width(), image.height());
  }

  /**
   * Merges, validates and writes a publish, then reads the files again. {@code blobs} holds the bytes of the image
   * puts by hash. Never throws for a refused or failed publish; the outcome says {@code rejected} with why.
   */
  public synchronized Outcome publish(String changeId, EditorSyncChangeset changeset, Map<String, byte[]> blobs) {
    int maximum = internalBytes();
    Current current;
    EditorSyncChangesetMerger.Merged merged;
    EditorSyncPublicationValidator.ValidatedProject validated;
    try {
      current = current(maximum);
      merged = EditorSyncChangesetMerger.merge(current.project().json(), changeset, blobs, current.unreadable(),
          current.unreadableImages());
      if (merged.changes() == 0) {
        return settled(merged.conflicts().isEmpty() ? "applied" : "conflict",
            message(0, merged.conflicts(), merged.restored()), merged.conflicts(), changeset, merged.restored(),
            Delta.none());
      }
      validated = validator.validate(EditorSyncPublicationValidator.Base.of(current.project()),
          new EditorSyncPublication(current.project().baseRevision(), merged.project()), maximum,
          current.project().json());
    } catch (EditorSyncProjectTooLargeException tooLarge) {
      return new Outcome("rejected", "Nothing was published: the workspace is larger than [editor] maxProjectMiB ("
          + settingMiB() + " MiB) allows. Raise maxProjectMiB in gloss.toml.", List.of(), Delta.none(), Delta.none());
    } catch (RuntimeException refused) {
      return new Outcome("rejected", "Nothing was published: " + safeMessage(refused) + ".", List.of(), Delta.none(),
          Delta.none());
    }
    List<EditorSyncConflict> conflicts = new ArrayList<>(merged.conflicts());
    for (EditorSyncPublicationValidator.DocumentKey key : validated.conflicts()) {
      conflicts.add(new EditorSyncConflict(key.kind().wireName(), key.id(), null, "changed"));
    }
    if (validated.noOp()) {
      String status = conflicts.isEmpty() ? "applied" : "conflict";
      return settled(status, message(0, conflicts, merged.restored()), conflicts, changeset, merged.restored(),
          Delta.none());
    }
    try {
      applier.apply(changeId, current.project(), validated, () -> current(maximum).project());
    } catch (StrandedPublishException stranded) {
      return settled("rejected", "The publish failed part way and could not be undone: " + safeMessage(stranded)
          + ". The files are left as the server holds them now.", List.of(), changeset, Set.of(), refresh());
    } catch (IOException | RuntimeException failed) {
      return settled("rejected", "Nothing was published: " + safeMessage(failed) + ".", List.of(), changeset, Set.of(),
          refresh());
    }
    return settled("applied", message(merged.changes(), conflicts, merged.restored()), conflicts, changeset,
        merged.restored(), refresh());
  }

  /**
   * Splits what the files showed into the publish's own changes (the entries it named that did not keep the server
   * copy, plus every shipped document it reset) and everyone else's.
   */
  private Outcome settled(String status, String message, List<EditorSyncConflict> conflicts,
                          EditorSyncChangeset changeset, Set<String> restored, Delta found) {
    Set<String> keys = new HashSet<>(restored);
    for (EditorSyncChangeset.DocumentChange change : changeset.documents()) {
      keys.add(EditorSyncChangesetMerger.key(change.kind(), change.id()));
    }
    Set<String> paths = new HashSet<>();
    for (EditorSyncChangeset.ImageChange change : changeset.images()) {
      paths.add(imagePath(change.path()).orElse(change.path()));
    }
    for (EditorSyncConflict conflict : conflicts) {
      if (EditorSyncConflict.IMAGE_KIND.equals(conflict.kind())) {
        paths.remove(conflict.id());
      } else {
        keys.remove(EditorSyncChangesetMerger.key(conflict.kind(), conflict.id()));
      }
    }
    List<DocumentRow> ownDocuments = new ArrayList<>();
    List<DocumentRow> otherDocuments = new ArrayList<>();
    for (DocumentRow row : found.documents()) {
      (keys.contains(EditorSyncChangesetMerger.key(row.kind(), row.id())) ? ownDocuments : otherDocuments).add(row);
    }
    Set<String> answered = new HashSet<>();
    ownDocuments.forEach((DocumentRow row) -> answered.add(EditorSyncChangesetMerger.key(row.kind(), row.id())));
    for (String key : restored) {
      int split = key.indexOf('\0');
      Optional<DocumentRow> row = view.row(key.substring(0, split), key.substring(split + 1));
      if (row.isPresent() && answered.add(key)) {
        ownDocuments.add(row.get());
      }
    }
    List<DocumentRef> ownDeleted = new ArrayList<>();
    List<DocumentRef> otherDeleted = new ArrayList<>();
    for (DocumentRef ref : found.deleted()) {
      (keys.contains(EditorSyncChangesetMerger.key(ref.kind(), ref.id())) ? ownDeleted : otherDeleted).add(ref);
    }
    List<ImageRow> ownImages = new ArrayList<>();
    List<ImageRow> otherImages = new ArrayList<>();
    for (ImageRow row : found.images()) {
      (paths.contains(row.path()) ? ownImages : otherImages).add(row);
    }
    List<String> ownRemoved = new ArrayList<>();
    List<String> otherRemoved = new ArrayList<>();
    for (String path : found.removedImages()) {
      (paths.contains(path) ? ownRemoved : otherRemoved).add(path);
    }
    return new Outcome(status, message, conflicts, new Delta(ownDocuments, ownDeleted, ownImages, ownRemoved, null, null),
        new Delta(otherDocuments, otherDeleted, otherImages, otherRemoved, null, null));
  }

  /** {@code path} as the workspace stores image paths, or empty when it is no valid image path. */
  public static Optional<String> imagePath(String path) {
    try {
      return Optional.of(EditorSyncSnapshotBuilder.normalizeRelative(path));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  /**
   * The workspace as one project for a publish, built again only when a readable file changed since the last build,
   * with the files that cannot be read named beside it. A publish judges documents and images only, so the lint and
   * history sections are left out.
   */
  private Current current(int maximum) {
    EditorSyncContentSnapshotBuilder.Listing listing = content.listForPublish(maximumBytes.getAsInt());
    String digest = EditorSyncContentSnapshotBuilder.digest(listing);
    if (!digest.equals(projectDigest)) {
      project = content.open(listing, maximum, new EditorSyncContentSnapshotBuilder.ServerSections() {
      });
      projectDigest = digest;
    }
    return new Current(project, listing.unreadable(), listing.unreadableImages());
  }

  /**
   * One plain sentence: how many changes landed and, by name, which kept the server copy; then which shipped
   * documents a delete reset instead.
   */
  static String message(int landed, List<EditorSyncConflict> conflicts, Set<String> restored) {
    String message = message(landed, conflicts);
    if (restored.isEmpty()) {
      return message;
    }
    List<String> names = new ArrayList<>();
    for (String key : new TreeSet<>(restored)) {
      names.add(key.replace('\0', ' '));
    }
    boolean one = names.size() == 1;
    return message + " Gloss ships " + String.join(", ", names) + " and puts " + (one ? "it" : "them")
        + " back when deleted, so " + (one ? "it was reset to the shipped copy." : "they were reset to the shipped copies.");
  }

  /** One plain sentence: how many changes landed and, by name, which kept the server copy. */
  static String message(int landed, List<EditorSyncConflict> conflicts) {
    if (conflicts.isEmpty()) {
      return landed == 0 ? "Nothing to change." : "Published " + landed + " change" + (landed == 1 ? "" : "s") + ".";
    }
    StringBuilder names = new StringBuilder();
    for (int index = 0; index < Math.min(CONFLICT_NAMES_SHOWN, conflicts.size()); index++) {
      if (index > 0) {
        names.append(", ");
      }
      names.append(conflicts.get(index).name());
    }
    if (conflicts.size() > CONFLICT_NAMES_SHOWN) {
      names.append(" and ").append(conflicts.size() - CONFLICT_NAMES_SHOWN).append(" more");
    }
    if (landed == 0) {
      return "Nothing was published: the server changed " + names + " after you opened them.";
    }
    return "Published " + landed + " change" + (landed == 1 ? "" : "s") + "; " + conflicts.size()
        + " kept the server copy: " + names + ".";
  }

  /** The owner's {@code maxProjectMiB}, as set. */
  private long settingMiB() {
    return (maximumBytes.getAsInt() + 1024L * 1024L - 1L) / (1024L * 1024L);
  }

  /** The in-process ceiling of the whole project, which holds images as base64 inside JSON. */
  private int internalBytes() {
    long owner = maximumBytes.getAsInt();
    return (int) Math.min(Integer.MAX_VALUE - 64L, owner * 2L + 32L * 1024L * 1024L);
  }

  private JsonObject buildMeta() {
    JsonObject built = new JsonObject();
    built.addProperty("format", "gloss-editor-meta");
    built.addProperty("version", META_VERSION);
    JsonArray kinds = new JsonArray();
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      JsonObject row = new JsonObject();
      row.addProperty("kind", kind.wireName());
      row.addProperty("storage", kind.storageName());
      row.addProperty("layout", kind.layout().name().toLowerCase(Locale.ROOT).replace('_', '-'));
      row.addProperty("versioned", kind.versioned());
      if (kind.singletonId() == null) {
        row.add("singletonId", JsonNull.INSTANCE);
      } else {
        row.addProperty("singletonId", kind.singletonId());
      }
      row.addProperty("scope", EditorScope.forKind(kind.wireName()).map(EditorScope::wireName).orElseThrow());
      kinds.add(row);
    }
    built.add("kinds", kinds);
    built.add("schemas", sections.schemas());
    built.add("defaults", sections.defaults());
    return built;
  }

  private static DocumentRow row(EditorSyncDocuments.Entry entry, String path) {
    boolean commands;
    try {
      commands = runsCommands(entry.kind(), JsonParser.parseString(entry.json()));
    } catch (RuntimeException unreadable) {
      commands = false;
    }
    return new DocumentRow(entry.kind(), entry.id(), path == null ? "" : path, entry.baseRevision(),
        entry.json().getBytes(StandardCharsets.UTF_8).length, entry.revision(), name(entry.id(), entry.json()), commands);
  }

  /**
   * True when publishing this document needs {@code publish:commands}: an action runs a command as the console, or
   * the document is a behavior with any command action, since its triggers run it as whoever fires them.
   */
  public static boolean runsCommands(String kind, JsonElement element) {
    return CommandActions.any(element, EditorSyncDocumentKind.BEHAVIOR.wireName().equals(kind)
        ? CommandActions::isCommand : CommandActions::runsAsServer);
  }

  private static String string(JsonObject object, String field) {
    JsonElement value = object.get(field);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
  }

  /**
   * A short label: the document's top-level {@code name}, {@code title} or {@code displayName} with colour codes
   * removed, else the last segment of its id; at most 80 characters.
   */
  static String name(String id, String json) {
    try {
      JsonElement parsed = JsonParser.parseString(json);
      if (parsed.isJsonObject()) {
        for (String field : List.of("name", "title", "displayName")) {
          String value = string(parsed.getAsJsonObject(), field);
          if (value != null) {
            String plain = value.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").strip();
            if (!plain.isEmpty()) {
              return plain.length() > NAME_LIMIT ? plain.substring(0, NAME_LIMIT) : plain;
            }
          }
        }
      }
    } catch (RuntimeException ignored) {
      // An unreadable body is named by its id.
    }
    String last = id.substring(id.lastIndexOf('/') + 1);
    return last.length() > NAME_LIMIT ? last.substring(0, NAME_LIMIT) : last;
  }

  static int compareRows(DocumentRow left, DocumentRow right) {
    int kind = left.kind().compareTo(right.kind());
    return kind != 0 ? kind : left.id().compareTo(right.id());
  }

  static String hash(JsonElement value) {
    return sha256(EditorSyncJson.canonical(value).getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static String safeMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
      current = current.getCause();
    }
    String message = current.getMessage();
    if (message == null || message.isBlank()) {
      return current.getClass().getSimpleName();
    }
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }
}
