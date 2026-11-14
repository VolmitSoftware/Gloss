package art.arcane.gloss.editor.sync;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentPresetCatalog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Reads the workspace (every document of every kind, and every image) from the data folder. Each file is read and
 * parsed again only when its size, modification time or file key moved, so a refresh after one change costs a
 * directory walk and one parse. {@link #moved} tells whether anything moved since the last listing without reading a
 * file.
 */
final class EditorSyncContentSnapshotBuilder {
  static final String WORKSPACE_SUBJECT_ID = "workspace";
  static final String WORKSPACE_KIND = "workspace";
  static final String SCHEMA_SKIPPED_CODE = "schema-skipped";
  static final String UNREADABLE_CODE = "unreadable";

  /** What a file was when it was read: a different size, time or key means it changed. */
  record Stamp(long size, long modified, Object key) {
  }

  /** One image file as read: its bytes and what validation found. */
  record ImageFile(String path, byte[] bytes, String hash, EditorSyncSnapshotBuilder.ValidatedImage image) {
  }

  /**
   * The workspace as the files are now: documents sorted by kind and id, images by path, and warnings. A tolerant
   * listing leaves out the files it could not read and names them in {@code unreadable} (document keys) and
   * {@code unreadableImages} (paths).
   */
  record Listing(List<EditorSyncDocuments.Entry> documents, Map<String, String> paths, List<ImageFile> images,
                 List<String> warnings, Set<String> unreadable, Set<String> unreadableImages) {
  }

  private record CachedDocument(Stamp stamp, Stamp presets, EditorSyncDocuments.Entry entry) {
  }

  private record CachedImage(Stamp stamp, ImageFile file) {
  }

  private final Path dataDirectory;
  private final Map<Path, CachedDocument> documentCache = new ConcurrentHashMap<>();
  private final Map<Path, CachedImage> imageCache = new ConcurrentHashMap<>();
  /** Every file the last tolerant listing (the one the catalog shows) saw, readable or not, with its stamp then. */
  private volatile Map<Path, Stamp> listed = Map.of();

  EditorSyncContentSnapshotBuilder(Path dataDirectory) {
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
  }

  EditorSyncProject open(int maximumBytes) {
    return open(maximumBytes, new ServerSections() {
    });
  }

  /** The whole workspace as one project; any file that cannot be read refuses the build. */
  EditorSyncProject open(int maximumBytes, ServerSections serverSections) {
    return open(list(false, maximumBytes), maximumBytes, serverSections);
  }

  /** The project of a listing read with {@code tolerant} false. */
  EditorSyncProject open(Listing listing, int maximumBytes, ServerSections serverSections) {
    Map<String, byte[]> images = new LinkedHashMap<>();
    for (ImageFile image : listing.images()) {
      images.put(image.path(), image.bytes());
    }
    List<String> warnings = new ArrayList<>(listing.warnings());
    warnings.addAll(warnings(listing.documents()));
    warnings.addAll(serverSections.warnings(listing.documents()));
    EditorSyncProjectSections sections = new EditorSyncProjectSections(warnings,
        serverSections.history(listing.documents()), serverSections.schemas(), serverSections.defaults());
    return project(listing.documents(), images, workspaceConstraints(), sections, maximumBytes);
  }

  /** A digest of what a listing holds: every document's kind, id and hash and every image's path and hash. */
  static String digest(Listing listing) {
    StringBuilder text = new StringBuilder();
    for (EditorSyncDocuments.Entry entry : listing.documents()) {
      text.append(entry.kind()).append('\0').append(entry.id()).append('\0').append(entry.baseRevision()).append('\n');
    }
    for (ImageFile image : listing.images()) {
      text.append(image.path()).append('\0').append(image.hash()).append('\n');
    }
    return EditorWorkspace.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Every document and image as the files are now. {@code tolerant}: a document or image that cannot be read is
   * left out and named in a warning; otherwise it refuses the listing. A tolerant listing is what {@link #moved}
   * compares against.
   */
  Listing list(boolean tolerant, int maximumBytes) {
    return list(tolerant, tolerant, maximumBytes);
  }

  /** A tolerant listing for a publish, which leaves {@link #moved} comparing against the catalog's last listing. */
  Listing listForPublish(int maximumBytes) {
    return list(true, false, maximumBytes);
  }

  private Listing list(boolean tolerant, boolean remember, int maximumBytes) {
    List<String> warnings = new ArrayList<>();
    Stamp presetsStamp = stamp(dataDirectory.resolve(DocumentPresetCatalog.FILE_NAME));
    DocumentPresetCatalog presets = readPresets();
    Map<Path, Stamp> stamps = new HashMap<>();
    List<EditorSyncDocuments.Entry> documents = new ArrayList<>();
    Map<String, String> paths = new LinkedHashMap<>();
    Set<String> unreadable = new HashSet<>();
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      for (Map.Entry<String, Path> file : files(kind).entrySet()) {
        Stamp stamp = stamp(file.getValue());
        stamps.put(file.getValue(), stamp);
        try {
          EditorSyncDocuments.Entry entry = readDocument(kind, file.getKey(), file.getValue(), stamp, presets,
              presetsStamp);
          documents.add(entry);
          paths.put(entry.kind() + '\0' + entry.id(), dataDirectory.relativize(file.getValue()).toString()
              .replace(java.io.File.separatorChar, '/'));
        } catch (RuntimeException failure) {
          if (DocumentEnvelope.isUnsupportedSchemaVersion(failure)) {
            warnings.add(SCHEMA_SKIPPED_CODE + "|" + kind.wireName() + "|" + file.getKey()
                + "||declares an unsupported schemaVersion");
            continue;
          }
          if (!tolerant) {
            throw failure;
          }
          unreadable.add(EditorSyncChangesetMerger.key(kind.wireName(), file.getKey()));
          warnings.add(warning(UNREADABLE_CODE + "|" + kind.wireName() + "|" + file.getKey() + "||"
              + safeMessage(failure)));
        }
        if (documents.size() > EditorSyncDocuments.MAX_DOCUMENTS) {
          throw new IllegalArgumentException("documents must contain at most " + EditorSyncDocuments.MAX_DOCUMENTS
              + " entries");
        }
      }
    }
    documentCache.keySet().retainAll(stamps.keySet());
    documents.sort(Comparator.comparing(EditorSyncDocuments.Entry::kind).thenComparing(EditorSyncDocuments.Entry::id));
    Set<String> unreadableImages = new HashSet<>();
    List<ImageFile> images = readImages(tolerant, maximumBytes, warnings, stamps, unreadableImages);
    if (remember) {
      listed = Map.copyOf(stamps);
    }
    return new Listing(List.copyOf(documents), Map.copyOf(paths), images, List.copyOf(warnings), Set.copyOf(unreadable),
        Set.copyOf(unreadableImages));
  }

  /**
   * True when a workspace file was added, removed or changed (size, time or file key) since the last tolerant
   * listing. Reads no file: one stat per file the listing covers.
   */
  boolean moved() {
    Map<Path, Stamp> before = listed;
    int[] seen = {0};
    try {
      for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
        Path target = dataDirectory.resolve(kind.storageName()).normalize();
        if (kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
          Stamp stamp = stamp(target);
          if (stamp != null) {
            seen[0]++;
            if (!stamp.equals(before.get(target))) {
              return true;
            }
          }
          continue;
        }
        int depth = kind.layout() == EditorSyncDocumentKind.Layout.TREE ? Integer.MAX_VALUE : 1;
        if (movedBelow(target, depth, true, before, seen)) {
          return true;
        }
      }
      if (movedBelow(dataDirectory.resolve("images"), Integer.MAX_VALUE, false, before, seen)) {
        return true;
      }
    } catch (IOException unreadable) {
      return true;
    }
    return seen[0] != before.size();
  }

  /** Walks one root, counting its files into {@code seen}; true at the first file whose stamp differs. */
  private static boolean movedBelow(Path root, int depth, boolean documents, Map<Path, Stamp> before, int[] seen)
      throws IOException {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    boolean[] moved = {false};
    Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), depth,
        new java.nio.file.SimpleFileVisitor<>() {
          @Override
          public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            String name = file.getFileName().toString();
            if (!attributes.isRegularFile() && !attributes.isSymbolicLink() || temporary(name)
                || documents && !name.endsWith(".json")) {
              return java.nio.file.FileVisitResult.CONTINUE;
            }
            seen[0]++;
            Stamp stamp = new Stamp(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                attributes.fileKey());
            if (!stamp.equals(before.get(file))) {
              moved[0] = true;
              return java.nio.file.FileVisitResult.TERMINATE;
            }
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure) {
            moved[0] = true;
            return java.nio.file.FileVisitResult.TERMINATE;
          }
        });
    return moved[0];
  }

  static EditorSyncProject project(List<EditorSyncDocuments.Entry> documents, Map<String, byte[]> images,
                                   JsonObject constraints, EditorSyncProjectSections sections, int maximumBytes) {
    List<EditorSyncDocuments.Entry> orderedDocuments = sorted(documents).stream()
        .map((EditorSyncDocuments.Entry document) -> document.withBaseRevision(
            EditorSyncDocuments.contentRevision(document.json())))
        .toList();
    JsonObject project = new JsonObject();
    project.addProperty("format", EditorSyncJson.PROJECT_FORMAT);
    project.addProperty("version", EditorSyncJson.PROJECT_VERSION);
    project.addProperty("kind", WORKSPACE_KIND);
    project.addProperty("subjectId", WORKSPACE_SUBJECT_ID);
    project.add("documents", EditorSyncDocuments.build(orderedDocuments));
    JsonArray imageArray = new JsonArray();
    Map<String, EditorSyncSnapshotBuilder.ValidatedImage> validatedImages = new TreeMap<>();
    for (Map.Entry<String, byte[]> image : new TreeMap<>(images).entrySet()) {
      String path = EditorSyncSnapshotBuilder.normalizeRelative(image.getKey());
      byte[] bytes = image.getValue();
      if (bytes.length > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
        throw new IllegalArgumentException("image exceeds the sync per-asset limit: " + path);
      }
      EditorSyncSnapshotBuilder.ValidatedImage validated = EditorSyncSnapshotBuilder.validateWorkspaceImage(path, bytes);
      validatedImages.put(path, validated);
      JsonObject value = new JsonObject();
      value.addProperty("path", path);
      value.addProperty("data", "data:" + validated.mediaType() + ";base64,"
          + Base64.getEncoder().encodeToString(bytes));
      imageArray.add(value);
    }
    if (imageArray.size() > EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT) {
      throw new IllegalArgumentException("images exceed " + EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT + " assets");
    }
    List<String> menuSources = orderedDocuments.stream()
        .filter((EditorSyncDocuments.Entry document) -> document.kind().equals(EditorSyncDocumentKind.MENU.wireName()))
        .map(EditorSyncDocuments.Entry::json)
        .toList();
    EditorSyncSnapshotBuilder.validateWorkspaceImageBudgets(validatedImages);
    EditorSyncSnapshotBuilder.validateReferencedImageBudgets(menuSources, validatedImages);
    project.add("images", imageArray);
    project.add("constraints", constraints.deepCopy());
    JsonArray warningArray = new JsonArray();
    List<String> orderedWarnings = sections.warnings().stream().sorted().toList();
    if (orderedWarnings.size() > EditorSyncSnapshotBuilder.MAX_WARNING_COUNT) {
      orderedWarnings = orderedWarnings.subList(0, EditorSyncSnapshotBuilder.MAX_WARNING_COUNT);
    }
    for (String warning : orderedWarnings) {
      if (warning == null || warning.length() > EditorSyncSnapshotBuilder.MAX_WARNING_CHARACTERS) {
        throw new IllegalArgumentException("sync project warning is invalid");
      }
      warningArray.add(warning);
    }
    project.add("warnings", warningArray);
    if (!sections.history().isEmpty()) {
      project.add("history", sections.history());
    }
    if (!sections.schemas().isEmpty()) {
      project.add("schemas", sections.schemas());
    }
    if (!sections.defaults().isEmpty()) {
      project.add("defaults", sections.defaults());
    }
    project.addProperty("baseRevision", EditorSyncJson.revision(project));
    return EditorSyncProject.validated(project, maximumBytes);
  }

  /** The constraints of the workspace: every kind may be edited, created and deleted. */
  static JsonObject workspaceConstraints() {
    JsonObject constraints = new JsonObject();
    constraints.addProperty("subjectId", WORKSPACE_SUBJECT_ID);
    JsonArray documentKinds = new JsonArray();
    JsonArray createDocumentKinds = new JsonArray();
    EditorSyncDocumentKind.ORDERED_WIRE_NAMES.forEach(documentKinds::add);
    EditorSyncDocumentKind.ORDERED_WIRE_NAMES.forEach(createDocumentKinds::add);
    constraints.add("documentKinds", documentKinds);
    constraints.add("createDocumentKinds", createDocumentKinds);
    constraints.addProperty("allowDeletes", true);
    return constraints;
  }

  /** The document files of one kind, by id, in path order. */
  private Map<String, Path> files(EditorSyncDocumentKind kind) {
    Path target = dataDirectory.resolve(kind.storageName()).normalize();
    Map<String, Path> files = new LinkedHashMap<>();
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return files;
    }
    if (kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
      files.put(kind.singletonId(), target);
      return files;
    }
    requireRealDirectory(target, kind.wireName() + " root");
    List<Path> listed;
    try (Stream<Path> stream = kind.layout() == EditorSyncDocumentKind.Layout.TREE ? Files.walk(target)
        : Files.list(target)) {
      listed = stream.sorted(Comparator.comparing(Path::toString)).toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot scan " + kind.wireName() + " documents", failure);
    }
    for (Path file : listed) {
      if (file.equals(target)) {
        continue;
      }
      if (Files.isSymbolicLink(file)) {
        throw new IllegalStateException("sync content contains a symbolic link: " + file);
      }
      if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      String fileName = file.getFileName().toString();
      if (!fileName.endsWith(".json") || temporary(fileName)) {
        continue;
      }
      String relative = target.relativize(file).toString().replace(java.io.File.separatorChar, '/');
      files.put(relative.substring(0, relative.length() - ".json".length()), file);
    }
    return files;
  }

  private EditorSyncDocuments.Entry readDocument(EditorSyncDocumentKind kind, String id, Path file, Stamp stamp,
                                                 DocumentPresetCatalog presets, Stamp presetsStamp) {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("sync document is not a regular non-symbolic file: " + file);
    }
    CachedDocument cached = documentCache.get(file);
    if (cached != null && cached.stamp().equals(stamp) && Objects.equals(cached.presets(), presetsStamp)) {
      return cached.entry();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length > EditorSyncDocuments.MAX_DOCUMENT_BYTES) {
        throw new IllegalArgumentException("sync document exceeds " + EditorSyncDocuments.MAX_DOCUMENT_BYTES
            + " bytes: " + kind.wireName() + " " + id);
      }
      String source = new String(bytes, StandardCharsets.UTF_8);
      EditorSyncDocumentKind.ParsedDocument parsed = kind.parse(id, source, presets);
      String wireSource = kind.wireSource(id, source, parsed, presets);
      EditorSyncDocuments.Entry entry = new EditorSyncDocuments.Entry(kind.wireName(), kind.canonicalId(id),
          parsed.revision(), wireSource, EditorSyncDocuments.contentRevision(wireSource));
      if (stamp != null) {
        documentCache.put(file, new CachedDocument(stamp, presetsStamp, entry));
      }
      return entry;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read sync document: " + file, failure);
    }
  }

  private List<ImageFile> readImages(boolean tolerant, int maximumBytes, List<String> warnings,
                                     Map<Path, Stamp> stamps, Set<String> unreadable) {
    Path root = dataDirectory.resolve("images");
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      imageCache.clear();
      return List.of();
    }
    requireRealDirectory(root, "image root");
    List<Path> listed;
    try (Stream<Path> stream = Files.walk(root)) {
      listed = stream.sorted(Comparator.comparing(Path::toString)).toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot scan image assets", failure);
    }
    Set<Path> seen = new HashSet<>();
    List<ImageFile> images = new ArrayList<>();
    long totalBytes = 0L;
    for (Path path : listed) {
      if (path.equals(root)) {
        continue;
      }
      if (Files.isSymbolicLink(path)) {
        throw new IllegalStateException("image content contains a symbolic link: " + path);
      }
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || temporary(path.getFileName().toString())) {
        continue;
      }
      String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
      seen.add(path);
      Stamp stamp = stamp(path);
      stamps.put(path, stamp);
      try {
        String normalized = EditorSyncSnapshotBuilder.normalizeRelative(relative);
        if (images.size() >= EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT) {
          throw new IllegalArgumentException("images exceed " + EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT + " assets");
        }
        ImageFile image = readImage(normalized, path, stamp);
        totalBytes += image.bytes().length;
        if (totalBytes > maximumBytes) {
          throw new EditorSyncProjectTooLargeException((int) Math.min(totalBytes, Integer.MAX_VALUE), maximumBytes);
        }
        images.add(image);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (!tolerant || failure instanceof EditorSyncProjectTooLargeException) {
          throw failure;
        }
        unreadable.add(relative);
        warnings.add(warning(UNREADABLE_CODE + "|image|" + relative + "||" + safeMessage(failure)));
      }
    }
    imageCache.keySet().retainAll(seen);
    return List.copyOf(images);
  }

  private ImageFile readImage(String normalized, Path path, Stamp stamp) {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("image content is not a regular file: " + path);
    }
    CachedImage cached = imageCache.get(path);
    if (cached != null && cached.stamp().equals(stamp)) {
      return cached.file();
    }
    try {
      long size = Files.size(path);
      if (size > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
        throw new IllegalArgumentException("image exceeds the sync per-asset limit: " + normalized);
      }
      byte[] content = Files.readAllBytes(path);
      EditorSyncSnapshotBuilder.ValidatedImage validated =
          EditorSyncSnapshotBuilder.validateWorkspaceImage(normalized, content);
      ImageFile file = new ImageFile(normalized, content, EditorWorkspace.sha256(content), validated);
      if (stamp != null) {
        imageCache.put(path, new CachedImage(stamp, file));
      }
      return file;
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read image asset: " + path, failure);
    }
  }

  private static Stamp stamp(Path file) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return new Stamp(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey());
    } catch (NoSuchFileException gone) {
      return null;
    } catch (IOException unreadable) {
      return null;
    }
  }

  private DocumentPresetCatalog readPresets() {
    try {
      return DocumentPresetCatalog.read(dataDirectory);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read preset catalog", failure);
    }
  }

  static List<String> warnings(List<EditorSyncDocuments.Entry> documents) {
    Set<String> menuIds = new HashSet<>();
    for (EditorSyncDocuments.Entry document : documents) {
      if (document.kind().equals(EditorSyncDocumentKind.MENU.wireName())) {
        menuIds.add(document.id());
      }
    }
    Set<String> missing = new TreeSet<>();
    for (EditorSyncDocuments.Entry document : documents) {
      if (!document.kind().equals(EditorSyncDocumentKind.MENU.wireName())) {
        continue;
      }
      Set<String> targets = new HashSet<>();
      EditorSyncSnapshotBuilder.collectTargets(JsonParser.parseString(document.json()), targets);
      targets.stream().filter((String target) -> !menuIds.contains(target)).forEach(missing::add);
    }
    return missing.stream().map((String target) -> "Referenced menu is not loaded: " + target).toList();
  }

  private static List<EditorSyncDocuments.Entry> sorted(java.util.Collection<EditorSyncDocuments.Entry> documents) {
    return documents.stream()
        .sorted(Comparator.comparing(EditorSyncDocuments.Entry::kind).thenComparing(EditorSyncDocuments.Entry::id))
        .toList();
  }

  private static void requireRealDirectory(Path path, String label) {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(label + " is not a real directory");
    }
  }

  private static String warning(String text) {
    String flat = text.replace('\n', ' ').replace('\r', ' ');
    return flat.length() > EditorSyncSnapshotBuilder.MAX_WARNING_CHARACTERS
        ? flat.substring(0, EditorSyncSnapshotBuilder.MAX_WARNING_CHARACTERS) : flat;
  }

  private static String safeMessage(Throwable failure) {
    Throwable current = failure;
    String message = null;
    while (current != null) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        message = current.getMessage();
      }
      current = current.getCause();
    }
    return message == null ? failure.getClass().getSimpleName() : message.replace('|', '/');
  }

  static boolean temporary(String name) {
    String normalized = name.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith(".") || normalized.startsWith("~") || normalized.startsWith("#")
        || normalized.endsWith("~") || normalized.endsWith(".tmp")
        || normalized.endsWith(".temp") || normalized.endsWith(".part")
        || normalized.endsWith(".swp") || normalized.endsWith(".swx")
        || normalized.endsWith(".bak") || normalized.contains(".tmp.")
        || normalized.contains(".temp.");
  }

  /**
   * The server-owned sections a caller contributes to a project. Every method has a do-nothing default so a
   * builder with no plugin behind it still produces a valid project.
   */
  interface ServerSections {
    default List<String> warnings(List<EditorSyncDocuments.Entry> documents) {
      return List.of();
    }

    default JsonArray history(List<EditorSyncDocuments.Entry> documents) {
      return new JsonArray();
    }

    default JsonObject schemas() {
      return new JsonObject();
    }

    default JsonObject defaults() {
      return new JsonObject();
    }
  }
}
