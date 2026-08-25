package art.arcane.gloss.editor.sync;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.panel.PanelDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

final class EditorSyncContentSnapshotBuilder {
  static final String WORKSPACE_SUBJECT_ID = "workspace";

  private final Path dataDirectory;

  EditorSyncContentSnapshotBuilder(Path dataDirectory) {
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize();
  }

  EditorSyncProject open(EditorSyncKind kind, String subjectId, int maximumBytes) {
    requireSubject(kind, subjectId);
    Map<DocumentKey, EditorSyncDocuments.Entry> allDocuments = readScopedDocuments(kind);
    List<EditorSyncDocuments.Entry> documents = switch (kind) {
      case WORKSPACE -> sorted(allDocuments.values());
      case MENU -> menuDocuments(allDocuments, subjectId);
      case PANEL -> panelDocuments(allDocuments, subjectId);
      default -> individualDocument(allDocuments, kind, subjectId);
    };
    Map<String, byte[]> images = kind == EditorSyncKind.WORKSPACE
        ? readAllImages(maximumBytes)
        : readReferencedImages(documents, maximumBytes);
    JsonObject constraints = constraints(kind, subjectId, documents);
    List<String> warnings = warnings(documents);
    return project(kind, subjectId, documents, images, constraints, warnings, maximumBytes);
  }

  List<String> subjectIds(EditorSyncKind kind) {
    if (kind == EditorSyncKind.WORKSPACE) {
      return List.of(WORKSPACE_SUBJECT_ID);
    }
    EditorSyncDocumentKind documentKind = EditorSyncDocumentKind.forSubject(kind);
    List<String> ids = new ArrayList<>();
    for (EditorSyncDocuments.Entry entry : readKind(documentKind)) {
      ids.add(entry.id());
    }
    ids.sort(String::compareTo);
    return List.copyOf(ids);
  }

  EditorSyncProject scoped(EditorSyncStoredSession session, int maximumBytes) {
    return open(session.kind(), session.subjectId(), maximumBytes);
  }

  static EditorSyncProject project(
      EditorSyncKind kind,
      String subjectId,
      List<EditorSyncDocuments.Entry> documents,
      Map<String, byte[]> images,
      JsonObject constraints,
      List<String> warnings,
      int maximumBytes) {
    List<EditorSyncDocuments.Entry> orderedDocuments = sorted(documents);
    JsonObject project = new JsonObject();
    project.addProperty("format", EditorSyncJson.PROJECT_FORMAT);
    project.addProperty("version", EditorSyncJson.PROTOCOL_VERSION);
    project.addProperty("kind", kind.wireName());
    project.addProperty("subjectId", subjectId);
    project.add("documents", EditorSyncDocuments.build(orderedDocuments));
    JsonArray imageArray = new JsonArray();
    Map<String, EditorSyncSnapshotBuilder.ValidatedImage> validatedImages = new TreeMap<>();
    for (Map.Entry<String, byte[]> image : new TreeMap<>(images).entrySet()) {
      String path = EditorSyncSnapshotBuilder.normalizeRelative(image.getKey());
      byte[] bytes = image.getValue();
      if (bytes.length > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
        throw new IllegalArgumentException("image exceeds the sync per-asset limit: " + path);
      }
      EditorSyncSnapshotBuilder.ValidatedImage validated = kind == EditorSyncKind.WORKSPACE
          ? EditorSyncSnapshotBuilder.validateWorkspaceImage(path, bytes)
          : EditorSyncSnapshotBuilder.validateImage(path, bytes);
      validatedImages.put(path, validated);
      JsonObject value = new JsonObject();
      value.addProperty("path", path);
      value.addProperty("data", "data:" + validated.mediaType() + ";base64,"
          + Base64.getEncoder().encodeToString(bytes));
      imageArray.add(value);
    }
    if (imageArray.size() > EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT) {
      throw new IllegalArgumentException("images exceed "
          + EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT + " assets");
    }
    List<String> menuSources = documents.stream()
        .filter(document -> document.kind().equals(EditorSyncDocumentKind.MENU.wireName()))
        .map(EditorSyncDocuments.Entry::json)
        .toList();
    EditorSyncSnapshotBuilder.validateWorkspaceImageBudgets(validatedImages);
    EditorSyncSnapshotBuilder.validateReferencedImageBudgets(menuSources, validatedImages);
    project.add("images", imageArray);
    project.add("constraints", constraints.deepCopy());
    JsonArray warningArray = new JsonArray();
    List<String> orderedWarnings = warnings.stream().sorted().toList();
    if (orderedWarnings.size() > EditorSyncSnapshotBuilder.MAX_WARNING_COUNT) {
      throw new IllegalArgumentException("sync project contains too many warnings");
    }
    for (String warning : orderedWarnings) {
      if (warning == null || warning.length() > EditorSyncSnapshotBuilder.MAX_WARNING_CHARACTERS) {
        throw new IllegalArgumentException("sync project warning is invalid");
      }
      warningArray.add(warning);
    }
    project.add("warnings", warningArray);
    project.addProperty("baseRevision", EditorSyncJson.revision(project));
    return EditorSyncProject.validated(project, maximumBytes);
  }

  private Map<DocumentKey, EditorSyncDocuments.Entry> readDocuments() {
    Map<DocumentKey, EditorSyncDocuments.Entry> documents = new LinkedHashMap<>();
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      for (EditorSyncDocuments.Entry entry : readKind(kind)) {
        DocumentKey key = new DocumentKey(kind, entry.id());
        if (documents.putIfAbsent(key, entry) != null) {
          throw new IllegalStateException("duplicate sync document: " + entry.kind() + " " + entry.id());
        }
        if (documents.size() > EditorSyncDocuments.MAX_DOCUMENTS) {
          throw new IllegalArgumentException("documents must contain at most "
              + EditorSyncDocuments.MAX_DOCUMENTS + " entries");
        }
      }
    }
    return Map.copyOf(documents);
  }

  private Map<DocumentKey, EditorSyncDocuments.Entry> readScopedDocuments(EditorSyncKind kind) {
    if (kind == EditorSyncKind.WORKSPACE) {
      return readDocuments();
    }
    List<EditorSyncDocumentKind> kinds = kind == EditorSyncKind.PANEL
        ? List.of(EditorSyncDocumentKind.MENU, EditorSyncDocumentKind.PANEL)
        : List.of(EditorSyncDocumentKind.forSubject(kind));
    Map<DocumentKey, EditorSyncDocuments.Entry> documents = new LinkedHashMap<>();
    for (EditorSyncDocumentKind documentKind : kinds) {
      for (EditorSyncDocuments.Entry entry : readKind(documentKind)) {
        documents.put(new DocumentKey(documentKind, entry.id()), entry);
      }
    }
    return Map.copyOf(documents);
  }

  private List<EditorSyncDocuments.Entry> readKind(EditorSyncDocumentKind kind) {
    Path target = dataDirectory.resolve(kind.storageName()).normalize();
    if (kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        return List.of();
      }
      String id = kind == EditorSyncDocumentKind.MOTD ? "motd" : "tablist";
      return readSupportedDocument(kind, id, target).map(List::of).orElseGet(List::of);
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    requireRealDirectory(target, kind.wireName() + " root");
    List<Path> files;
    try (Stream<Path> stream = kind.layout() == EditorSyncDocumentKind.Layout.TREE
        ? Files.walk(target)
        : Files.list(target)) {
      files = stream.sorted(Comparator.comparing(Path::toString)).toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot scan " + kind.wireName() + " documents", failure);
    }
    List<EditorSyncDocuments.Entry> documents = new ArrayList<>();
    for (Path file : files) {
      if (file.equals(target)) {
        continue;
      }
      if (Files.isSymbolicLink(file)) {
        throw new IllegalStateException("sync content contains a symbolic link: " + file);
      }
      if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("sync content is not a regular file: " + file);
      }
      String fileName = file.getFileName().toString();
      if (!fileName.endsWith(".json") || temporary(fileName)) {
        continue;
      }
      String relative = target.relativize(file).toString()
          .replace(java.io.File.separatorChar, '/');
      String id = relative.substring(0, relative.length() - ".json".length());
      readSupportedDocument(kind, id, file).ifPresent(documents::add);
    }
    documents.sort(Comparator.comparing(EditorSyncDocuments.Entry::id));
    return List.copyOf(documents);
  }

  private Optional<EditorSyncDocuments.Entry> readSupportedDocument(
      EditorSyncDocumentKind kind, String id, Path file) {
    try {
      return Optional.of(readDocument(kind, id, file));
    } catch (RuntimeException failure) {
      if (DocumentEnvelope.isUnsupportedSchemaVersion(failure)) {
        return Optional.empty();
      }
      throw failure;
    }
  }

  private EditorSyncDocuments.Entry readDocument(EditorSyncDocumentKind kind, String id, Path file) {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("sync document is not a regular non-symbolic file: " + file);
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length > EditorSyncDocuments.MAX_DOCUMENT_BYTES) {
        throw new IllegalArgumentException("sync document exceeds "
            + EditorSyncDocuments.MAX_DOCUMENT_BYTES + " bytes: " + kind.wireName() + " " + id);
      }
      String source = new String(bytes, StandardCharsets.UTF_8);
      EditorSyncDocumentKind.ParsedDocument parsed = kind.parse(id, source);
      String wireSource = kind.wireSource(id, source, parsed);
      return new EditorSyncDocuments.Entry(kind.wireName(), kind.canonicalId(id),
          parsed.revision(), wireSource);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read sync document: " + file, failure);
    }
  }

  private List<EditorSyncDocuments.Entry> individualDocument(
      Map<DocumentKey, EditorSyncDocuments.Entry> allDocuments,
      EditorSyncKind kind,
      String subjectId) {
    EditorSyncDocumentKind documentKind = EditorSyncDocumentKind.forSubject(kind);
    EditorSyncDocuments.Entry document = allDocuments.get(new DocumentKey(documentKind, subjectId));
    if (document == null) {
      throw new IllegalArgumentException("unknown " + kind.wireName() + ": " + subjectId);
    }
    return List.of(document);
  }

  private List<EditorSyncDocuments.Entry> menuDocuments(
      Map<DocumentKey, EditorSyncDocuments.Entry> allDocuments, String subjectId) {
    return individualDocument(allDocuments, EditorSyncKind.MENU, subjectId);
  }

  private List<EditorSyncDocuments.Entry> panelDocuments(
      Map<DocumentKey, EditorSyncDocuments.Entry> allDocuments, String subjectId) {
    EditorSyncDocuments.Entry panel = allDocuments.get(
        new DocumentKey(EditorSyncDocumentKind.PANEL, subjectId));
    if (panel == null) {
      throw new IllegalArgumentException("unknown panel: " + subjectId);
    }
    PanelDefinition definition = (PanelDefinition) EditorSyncDocumentKind.PANEL
        .parse(subjectId, panel.json()).value();
    Map<String, EditorSyncDocuments.Entry> menus = new TreeMap<>();
    for (Map.Entry<DocumentKey, EditorSyncDocuments.Entry> entry : allDocuments.entrySet()) {
      if (entry.getKey().kind() == EditorSyncDocumentKind.MENU) {
        menus.put(entry.getKey().id(), entry.getValue());
      }
    }
    List<EditorSyncDocuments.Entry> selected = new ArrayList<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.add(definition.rootMenuId());
    while (!pending.isEmpty()) {
      String id = pending.removeFirst();
      if (!visited.add(id)) {
        continue;
      }
      if (visited.size() > EditorSyncSnapshotBuilder.MAX_MENU_COUNT) {
        throw new IllegalArgumentException("panel menu graph exceeds "
            + EditorSyncSnapshotBuilder.MAX_MENU_COUNT + " menus");
      }
      EditorSyncDocuments.Entry menu = menus.get(id);
      if (menu == null) {
        continue;
      }
      selected.add(menu);
      Set<String> targets = new TreeSet<>();
      EditorSyncSnapshotBuilder.collectTargets(JsonParser.parseString(menu.json()), targets);
      pending.addAll(targets);
    }
    if (selected.stream().noneMatch(entry -> entry.id().equals(definition.rootMenuId()))) {
      throw new IllegalArgumentException("panel root menu is not loaded: " + definition.rootMenuId());
    }
    selected.add(panel);
    return sorted(selected);
  }

  private Map<String, byte[]> readReferencedImages(
      List<EditorSyncDocuments.Entry> documents, int maximumBytes) {
    Set<String> referenced = new TreeSet<>();
    for (EditorSyncDocuments.Entry document : documents) {
      if (document.kind().equals(EditorSyncDocumentKind.MENU.wireName())) {
        EditorSyncSnapshotBuilder.collectImagePaths(JsonParser.parseString(document.json()), referenced);
      }
    }
    if (referenced.size() > EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT) {
      throw new IllegalArgumentException("images exceed "
          + EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT + " assets");
    }
    Map<String, byte[]> images = new LinkedHashMap<>();
    Path root = dataDirectory.resolve("images");
    long totalBytes = 0L;
    for (String path : referenced) {
      try {
        Path file = EditorSyncSnapshotBuilder.resolveConfinedFile(root, path, true);
        long bytes = Files.size(file);
        if (bytes > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
          throw new IllegalArgumentException("image exceeds the sync per-asset limit: " + path);
        }
        totalBytes += bytes;
        if (totalBytes > maximumBytes) {
          throw new EditorSyncProjectTooLargeException((int) Math.min(totalBytes, Integer.MAX_VALUE),
              maximumBytes);
        }
        byte[] content = Files.readAllBytes(file);
        EditorSyncSnapshotBuilder.validateImage(path, content);
        images.put(path, content);
      } catch (IOException failure) {
        throw new IllegalStateException("referenced image cannot be synchronized: " + path, failure);
      }
    }
    return Map.copyOf(images);
  }

  private Map<String, byte[]> readAllImages(int maximumBytes) {
    Path root = dataDirectory.resolve("images");
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return Map.of();
    }
    requireRealDirectory(root, "image root");
    List<Path> paths;
    try (Stream<Path> stream = Files.walk(root)) {
      paths = stream.sorted(Comparator.comparing(Path::toString)).toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot scan image assets", failure);
    }
    Map<String, byte[]> images = new LinkedHashMap<>();
    long totalBytes = 0L;
    for (Path path : paths) {
      if (path.equals(root)) {
        continue;
      }
      if (Files.isSymbolicLink(path)) {
        throw new IllegalStateException("image content contains a symbolic link: " + path);
      }
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("image content is not a regular file: " + path);
      }
      String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
      if (temporary(path.getFileName().toString())) {
        continue;
      }
      String normalized = EditorSyncSnapshotBuilder.normalizeRelative(relative);
      try {
        if (images.size() >= EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT) {
          throw new IllegalArgumentException("images exceed "
              + EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT + " assets");
        }
        long bytes = Files.size(path);
        if (bytes > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
          throw new IllegalArgumentException(
              "image exceeds the sync per-asset limit: " + normalized);
        }
        totalBytes += bytes;
        if (totalBytes > maximumBytes) {
          throw new EditorSyncProjectTooLargeException((int) Math.min(totalBytes, Integer.MAX_VALUE),
              maximumBytes);
        }
        byte[] content = Files.readAllBytes(path);
        EditorSyncSnapshotBuilder.validateWorkspaceImage(normalized, content);
        images.put(normalized, content);
      } catch (IOException failure) {
        throw new IllegalStateException("cannot read image asset: " + path, failure);
      }
    }
    return Map.copyOf(images);
  }

  private JsonObject constraints(EditorSyncKind kind, String subjectId,
                                 List<EditorSyncDocuments.Entry> documents) {
    JsonObject constraints = new JsonObject();
    constraints.addProperty("subjectId", subjectId);
    JsonArray documentKinds = new JsonArray();
    JsonArray createDocumentKinds = new JsonArray();
    if (kind == EditorSyncKind.WORKSPACE) {
      EditorSyncDocumentKind.ORDERED_WIRE_NAMES.forEach(documentKinds::add);
      EditorSyncDocumentKind.ORDERED_WIRE_NAMES.forEach(createDocumentKinds::add);
      constraints.add("documentKinds", documentKinds);
      constraints.add("createDocumentKinds", createDocumentKinds);
      constraints.addProperty("allowDeletes", true);
      return constraints;
    }
    if (kind == EditorSyncKind.PANEL) {
      documentKinds.add(EditorSyncDocumentKind.MENU.wireName());
      documentKinds.add(EditorSyncDocumentKind.PANEL.wireName());
      createDocumentKinds.add(EditorSyncDocumentKind.MENU.wireName());
    } else {
      documentKinds.add(EditorSyncDocumentKind.forSubject(kind).wireName());
    }
    constraints.add("documentKinds", documentKinds);
    constraints.add("createDocumentKinds", createDocumentKinds);
    constraints.addProperty("allowDeletes", false);
    if (kind == EditorSyncKind.PANEL) {
      EditorSyncDocuments.Entry panel = documents.stream()
          .filter(entry -> entry.kind().equals(EditorSyncDocumentKind.PANEL.wireName()))
          .findFirst()
          .orElseThrow();
      PanelDefinition definition = (PanelDefinition) EditorSyncDocumentKind.PANEL
          .parse(panel.id(), panel.json()).value();
      String rootMenuId = definition.rootMenuId();
      int separator = rootMenuId.lastIndexOf('/');
      constraints.addProperty("newMenuPrefix", separator >= 0
          ? rootMenuId.substring(0, separator + 1)
          : rootMenuId + "/");
      constraints.addProperty("newImagePrefix", "sync/" + subjectId + "/");
    } else if (kind == EditorSyncKind.MENU) {
      constraints.addProperty("newImagePrefix", "sync/menus/" + subjectId + "/");
    }
    return constraints;
  }

  private List<String> warnings(List<EditorSyncDocuments.Entry> documents) {
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
      targets.stream().filter(target -> !menuIds.contains(target)).forEach(missing::add);
    }
    return missing.stream().map(target -> "Referenced menu is not loaded: " + target).toList();
  }

  private static List<EditorSyncDocuments.Entry> sorted(
      java.util.Collection<EditorSyncDocuments.Entry> documents) {
    return documents.stream()
        .sorted(Comparator.comparing(EditorSyncDocuments.Entry::kind)
            .thenComparing(EditorSyncDocuments.Entry::id))
        .toList();
  }

  private static void requireSubject(EditorSyncKind kind, String subjectId) {
    if (kind == EditorSyncKind.WORKSPACE) {
      if (!WORKSPACE_SUBJECT_ID.equals(subjectId)) {
        throw new IllegalArgumentException("workspace subjectId must be workspace");
      }
      return;
    }
    EditorSyncDocumentKind.forSubject(kind).canonicalId(subjectId);
  }

  private static void requireRealDirectory(Path path, String label) {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(label + " is not a real directory");
    }
  }

  private static boolean temporary(String name) {
    String normalized = name.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith(".") || normalized.startsWith("~") || normalized.startsWith("#")
        || normalized.endsWith("~") || normalized.endsWith(".tmp")
        || normalized.endsWith(".temp") || normalized.endsWith(".part")
        || normalized.endsWith(".swp") || normalized.endsWith(".swx")
        || normalized.endsWith(".bak") || normalized.contains(".tmp.")
        || normalized.contains(".temp.");
  }

  record DocumentKey(EditorSyncDocumentKind kind, String id) {
    DocumentKey {
      kind = Objects.requireNonNull(kind, "kind");
      id = kind.canonicalId(id);
    }
  }
}
