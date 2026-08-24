package art.arcane.gloss.editor.sync;

import art.arcane.gloss.panel.PanelDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

final class EditorSyncPublicationValidator {
  private static final Gson GSON = new GsonBuilder()
      .serializeNulls()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create();
  private static final Set<String> ACCEPTED_MEDIA_TYPES = Set.of(
      "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

  ValidatedProject validate(EditorSyncStoredSession session, EditorSyncPublication publication,
                            int maximumBytes) {
    if (!session.baseRevision().equals(publication.baseRevision())) {
      throw new IllegalArgumentException("publication baseRevision does not match the session base");
    }
    EditorSyncProject published = EditorSyncProject.validated(publication.snapshot(), maximumBytes);
    validateTopLevel(published);
    if (published.kind() != session.kind()) {
      throw new IllegalArgumentException("publication kind does not match the session");
    }
    if (!published.subjectId().equals(session.subjectId())) {
      throw new IllegalArgumentException("publication subject does not match the session");
    }
    requireImmutableConstraints(session, published.json());
    Map<DocumentKey, ParsedEntry> baseDocuments = parseDocuments(session.baseProject());
    Map<DocumentKey, ParsedEntry> publishedDocuments = parseDocuments(published.json());
    enforceDocumentScope(session, baseDocuments, publishedDocuments);
    Map<DocumentKey, ParsedEntry> appliedDocuments = applyServerRevisions(
        baseDocuments, publishedDocuments);
    validatePanels(session, baseDocuments, appliedDocuments);
    Map<String, byte[]> baseImages = parseImages(session.baseProject(), maximumBytes,
        session.kind() == EditorSyncKind.WORKSPACE);
    Map<String, byte[]> publishedImages = parseImages(published.json(), maximumBytes,
        session.kind() == EditorSyncKind.WORKSPACE);
    enforceImageScope(session, baseImages, publishedImages);
    validateReferences(session, baseImages, appliedDocuments, publishedImages);

    List<EditorSyncDocuments.Entry> appliedEntries = appliedDocuments.values().stream()
        .map(ParsedEntry::entry)
        .sorted(Comparator.comparing(EditorSyncDocuments.Entry::kind)
            .thenComparing(EditorSyncDocuments.Entry::id))
        .toList();
    JsonObject constraints = EditorSyncJson.requireObject(session.baseProject(), "constraints");
    EditorSyncProject appliedProject = EditorSyncContentSnapshotBuilder.project(
        session.kind(), session.subjectId(), appliedEntries, publishedImages, constraints,
        warnings(appliedEntries), maximumBytes);
    Set<EditorSyncDocumentKind> changedKinds = changedKinds(baseDocuments, appliedDocuments);
    boolean imagesChanged = !sameImages(baseImages, publishedImages);
    return new ValidatedProject(appliedProject, baseDocuments, appliedDocuments,
        baseImages, publishedImages, changedKinds, imagesChanged);
  }

  ValidatedProject validateBase(EditorSyncStoredSession session, int maximumBytes) {
    return validate(session, new EditorSyncPublication(1L, session.baseRevision(),
        session.baseProject()), maximumBytes);
  }

  private void validateTopLevel(EditorSyncProject project) {
    requireExactKeys(project.json(), Set.of("format", "version", "kind", "subjectId",
        "documents", "images", "constraints", "warnings", "baseRevision"), "sync project");
    JsonArray warnings = EditorSyncJson.requireArray(project.json(), "warnings");
    if (warnings.size() > EditorSyncSnapshotBuilder.MAX_WARNING_COUNT) {
      throw new IllegalArgumentException("sync project contains too many warnings");
    }
    for (JsonElement warning : warnings) {
      if (!warning.isJsonPrimitive() || !warning.getAsJsonPrimitive().isString()
          || warning.getAsString().length() > EditorSyncSnapshotBuilder.MAX_WARNING_CHARACTERS) {
        throw new IllegalArgumentException("sync project warning is invalid");
      }
    }
  }

  private void requireImmutableConstraints(EditorSyncStoredSession session, JsonObject project) {
    JsonObject stored = EditorSyncJson.requireObject(session.baseProject(), "constraints");
    JsonObject published = EditorSyncJson.requireObject(project, "constraints");
    if (!EditorSyncJson.canonical(stored).equals(EditorSyncJson.canonical(published))) {
      throw new IllegalArgumentException("sync publication cannot change its capability constraints");
    }
    validateConstraintShape(session, stored);
  }

  private void validateConstraintShape(EditorSyncStoredSession session, JsonObject constraints) {
    Set<String> expectedKeys = switch (session.kind()) {
      case MENU -> Set.of("subjectId", "documentKinds", "createDocumentKinds",
          "allowDeletes", "newImagePrefix");
      case PANEL -> Set.of("subjectId", "documentKinds", "createDocumentKinds",
          "allowDeletes", "newMenuPrefix", "newImagePrefix");
      default -> Set.of("subjectId", "documentKinds", "createDocumentKinds", "allowDeletes");
    };
    requireExactKeys(constraints, expectedKeys, "sync constraints");
    if (!session.subjectId().equals(EditorSyncJson.requireString(constraints, "subjectId"))) {
      throw new IllegalArgumentException("sync constraints do not match the session subject");
    }
    List<String> documentKinds = sortedUniqueKinds(
        EditorSyncJson.requireArray(constraints, "documentKinds"), "documentKinds");
    List<String> createKinds = sortedUniqueKinds(
        EditorSyncJson.requireArray(constraints, "createDocumentKinds"), "createDocumentKinds");
    boolean allowDeletes = requireBoolean(constraints, "allowDeletes");
    List<String> expectedDocumentKinds;
    List<String> expectedCreateKinds;
    if (session.kind() == EditorSyncKind.WORKSPACE) {
      expectedDocumentKinds = EditorSyncDocumentKind.ORDERED_WIRE_NAMES;
      expectedCreateKinds = EditorSyncDocumentKind.ORDERED_WIRE_NAMES;
      if (!allowDeletes) {
        throw new IllegalArgumentException("workspace sync constraints must allow deletes");
      }
    } else if (session.kind() == EditorSyncKind.PANEL) {
      expectedDocumentKinds = List.of("menu", "panel");
      expectedCreateKinds = List.of("menu");
      if (allowDeletes) {
        throw new IllegalArgumentException("individual sync constraints must prohibit deletes");
      }
      JsonObject panel = panelJson(session.baseProject(), session.subjectId());
      String root = EditorSyncJson.requireString(panel, "rootMenuId");
      int separator = root.lastIndexOf('/');
      String expectedMenuPrefix = separator >= 0 ? root.substring(0, separator + 1) : root + "/";
      if (!expectedMenuPrefix.equals(EditorSyncJson.requireString(constraints, "newMenuPrefix"))
          || !("sync/" + session.subjectId() + "/")
          .equals(EditorSyncJson.requireString(constraints, "newImagePrefix"))) {
        throw new IllegalArgumentException("panel sync creation prefix is invalid");
      }
    } else {
      expectedDocumentKinds = List.of(EditorSyncDocumentKind.forSubject(session.kind()).wireName());
      expectedCreateKinds = List.of();
      if (allowDeletes) {
        throw new IllegalArgumentException("individual sync constraints must prohibit deletes");
      }
      if (session.kind() == EditorSyncKind.MENU
          && !("sync/menus/" + session.subjectId() + "/")
          .equals(EditorSyncJson.requireString(constraints, "newImagePrefix"))) {
        throw new IllegalArgumentException("menu sync image creation prefix is invalid");
      }
    }
    if (!documentKinds.equals(expectedDocumentKinds) || !createKinds.equals(expectedCreateKinds)) {
      throw new IllegalArgumentException("sync constraint document kinds are invalid");
    }
  }

  private Map<DocumentKey, ParsedEntry> parseDocuments(JsonObject project) {
    Map<DocumentKey, ParsedEntry> documents = new LinkedHashMap<>();
    for (EditorSyncDocuments.Entry entry : EditorSyncDocuments.parse(project)) {
      EditorSyncDocumentKind kind = EditorSyncDocumentKind.parseWireName(entry.kind());
      EditorSyncDocumentKind.ParsedDocument parsed = kind.parse(entry.id(), entry.json());
      if (!Objects.equals(entry.revision(), parsed.revision())) {
        throw new IllegalArgumentException("sync document entry revision does not match its JSON: "
            + entry.kind() + " " + entry.id());
      }
      DocumentKey key = new DocumentKey(kind, entry.id());
      if (documents.putIfAbsent(key, new ParsedEntry(entry, parsed.value())) != null) {
        throw new IllegalArgumentException("duplicate sync document: " + entry.kind() + " " + entry.id());
      }
    }
    return Map.copyOf(documents);
  }

  private void enforceDocumentScope(EditorSyncStoredSession session,
                                    Map<DocumentKey, ParsedEntry> base,
                                    Map<DocumentKey, ParsedEntry> published) {
    JsonObject constraints = EditorSyncJson.requireObject(session.baseProject(), "constraints");
    Set<String> allowedKinds = new HashSet<>(sortedUniqueKinds(
        EditorSyncJson.requireArray(constraints, "documentKinds"), "documentKinds"));
    Set<String> createKinds = new HashSet<>(sortedUniqueKinds(
        EditorSyncJson.requireArray(constraints, "createDocumentKinds"), "createDocumentKinds"));
    for (DocumentKey key : published.keySet()) {
      if (!allowedKinds.contains(key.kind().wireName())) {
        throw new IllegalArgumentException("document kind is outside the session capability: "
            + key.kind().wireName());
      }
      if (!base.containsKey(key)) {
        if (!createKinds.contains(key.kind().wireName())) {
          throw new IllegalArgumentException("sync cannot create " + key.kind().wireName()
              + " documents in this session");
        }
        if (session.kind() == EditorSyncKind.PANEL && key.kind() == EditorSyncDocumentKind.MENU
            && !key.id().startsWith(EditorSyncJson.requireString(constraints, "newMenuPrefix"))) {
          throw new IllegalArgumentException("new menu is outside the session prefix: " + key.id());
        }
      }
    }
    if (!requireBoolean(constraints, "allowDeletes") && !published.keySet().containsAll(base.keySet())) {
      throw new IllegalArgumentException("individual sync sessions cannot delete documents");
    }
    if (session.kind() == EditorSyncKind.WORKSPACE) {
      return;
    }
    DocumentKey subject = new DocumentKey(EditorSyncDocumentKind.forSubject(session.kind()),
        session.subjectId());
    if (!published.containsKey(subject)) {
      throw new IllegalArgumentException("sync publication is missing its subject document");
    }
    if (session.kind() != EditorSyncKind.PANEL && published.size() != 1) {
      throw new IllegalArgumentException("individual sync may publish only its subject document");
    }
    if (session.kind() == EditorSyncKind.PANEL) {
      long panels = published.keySet().stream()
          .filter(key -> key.kind() == EditorSyncDocumentKind.PANEL)
          .count();
      if (panels != 1L) {
        throw new IllegalArgumentException("panel sync must publish exactly its subject panel");
      }
    }
  }

  private Map<DocumentKey, ParsedEntry> applyServerRevisions(
      Map<DocumentKey, ParsedEntry> base,
      Map<DocumentKey, ParsedEntry> published) {
    Map<DocumentKey, ParsedEntry> applied = new LinkedHashMap<>();
    for (Map.Entry<DocumentKey, ParsedEntry> publishedEntry : published.entrySet()) {
      DocumentKey key = publishedEntry.getKey();
      ParsedEntry incoming = publishedEntry.getValue();
      ParsedEntry previous = base.get(key);
      if (previous == null) {
        if (key.kind().versioned() && incoming.entry().revision() != 1L) {
          throw new IllegalArgumentException("new versioned documents must start at revision 1: "
              + key.kind().wireName() + " " + key.id());
        }
        applied.put(key, normalizeNew(incoming));
        continue;
      }
      if (!key.kind().versioned()) {
        applied.put(key, sameSource(previous.entry().json(), incoming.entry().json())
            ? previous
            : normalizeNew(incoming));
        continue;
      }
      long previousRevision = Objects.requireNonNull(previous.entry().revision());
      if (!Objects.equals(incoming.entry().revision(), previousRevision)) {
        throw new IllegalArgumentException("document revision is server-owned: "
            + key.kind().wireName() + " " + key.id());
      }
      if (sameVersionedContent(previous.entry().json(), incoming.entry().json())) {
        applied.put(key, previous);
        continue;
      }
      if (previousRevision == EditorSyncJson.MAX_SAFE_INTEGER) {
        throw new IllegalArgumentException("document revision overflow: "
            + key.kind().wireName() + " " + key.id());
      }
      JsonObject changed = JsonParser.parseString(incoming.entry().json()).getAsJsonObject();
      changed.addProperty("revision", previousRevision + 1L);
      String persistedShape = GSON.toJson(changed) + System.lineSeparator();
      EditorSyncDocumentKind.ParsedDocument parsed = key.kind().parse(key.id(), persistedShape);
      String normalized = key.kind().wireSource(key.id(), persistedShape, parsed);
      EditorSyncDocuments.Entry revised = new EditorSyncDocuments.Entry(
          key.kind().wireName(), key.id(), previousRevision + 1L, normalized);
      applied.put(key, new ParsedEntry(revised, parsed.value()));
    }
    return Map.copyOf(applied);
  }

  private ParsedEntry normalizeNew(ParsedEntry incoming) {
    EditorSyncDocumentKind kind = EditorSyncDocumentKind.parseWireName(incoming.entry().kind());
    String persistedShape = ensureLineEnd(incoming.entry().json());
    EditorSyncDocumentKind.ParsedDocument parsed = kind.parse(incoming.entry().id(), persistedShape);
    String source = kind.wireSource(incoming.entry().id(), persistedShape, parsed);
    if (source.equals(incoming.entry().json())) {
      return incoming;
    }
    EditorSyncDocuments.Entry normalized = new EditorSyncDocuments.Entry(
        incoming.entry().kind(), incoming.entry().id(), incoming.entry().revision(), source);
    return new ParsedEntry(normalized, parsed.value());
  }

  private void validatePanels(EditorSyncStoredSession session,
                              Map<DocumentKey, ParsedEntry> base,
                              Map<DocumentKey, ParsedEntry> applied) {
    Map<UUID, String> uuidOwners = new HashMap<>();
    Set<String> menuIds = new HashSet<>();
    for (DocumentKey key : applied.keySet()) {
      if (key.kind() == EditorSyncDocumentKind.MENU) {
        menuIds.add(key.id());
      }
    }
    for (Map.Entry<DocumentKey, ParsedEntry> entry : applied.entrySet()) {
      if (entry.getKey().kind() != EditorSyncDocumentKind.PANEL) {
        continue;
      }
      PanelDefinition panel = (PanelDefinition) entry.getValue().value();
      if (!menuIds.contains(panel.rootMenuId())) {
        throw new IllegalArgumentException("panel root menu is absent from the publication: "
            + panel.id());
      }
      String previousOwner = uuidOwners.putIfAbsent(panel.uuid(), panel.id());
      if (previousOwner != null) {
        throw new IllegalArgumentException("panel uuid is already used by " + previousOwner);
      }
      ParsedEntry previousEntry = base.get(entry.getKey());
      if (previousEntry != null) {
        PanelDefinition previous = (PanelDefinition) previousEntry.value();
        if (!previous.uuid().equals(panel.uuid())
            || previous.schemaVersion() != panel.schemaVersion()) {
          throw new IllegalArgumentException("panel uuid and schemaVersion are server-owned: "
              + panel.id());
        }
      }
    }
    if (session.kind() == EditorSyncKind.PANEL) {
      ParsedEntry panelEntry = applied.get(new DocumentKey(EditorSyncDocumentKind.PANEL,
          session.subjectId()));
      PanelDefinition panel = (PanelDefinition) Objects.requireNonNull(panelEntry).value();
      Set<String> reachable = reachableMenus(panel, applied);
      for (DocumentKey key : applied.keySet()) {
        if (key.kind() == EditorSyncDocumentKind.MENU && !base.containsKey(key)
            && !reachable.contains(key.id())) {
          throw new IllegalArgumentException("new menu is unreachable from the panel root: " + key.id());
        }
      }
    }
  }

  private Map<String, byte[]> parseImages(JsonObject project, int maximumBytes,
                                          boolean workspace) {
    JsonArray values = EditorSyncJson.requireArray(project, "images");
    if (values.size() > EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT) {
      throw new IllegalArgumentException("images exceed "
          + EditorSyncSnapshotBuilder.MAX_IMAGE_COUNT + " assets");
    }
    Map<String, byte[]> images = new LinkedHashMap<>();
    Map<String, EditorSyncSnapshotBuilder.ValidatedImage> validated = new LinkedHashMap<>();
    int totalBytes = 0;
    String previousPath = null;
    for (JsonElement value : values) {
      if (!value.isJsonObject()) {
        throw new IllegalArgumentException("image entry must be an object");
      }
      JsonObject entry = value.getAsJsonObject();
      requireExactKeys(entry, Set.of("path", "data"), "image entry");
      String path = EditorSyncSnapshotBuilder.normalizeRelative(
          EditorSyncJson.requireString(entry, "path"));
      if (previousPath != null && previousPath.compareTo(path) >= 0) {
        throw new IllegalArgumentException("sync images must be sorted by path and unique");
      }
      previousPath = path;
      DecodedImage decoded = decodeDataUrl(EditorSyncJson.requireString(entry, "data"));
      if (decoded.data().length > EditorSyncSnapshotBuilder.MAX_IMAGE_BYTES) {
        throw new IllegalArgumentException("image exceeds the sync per-asset limit: " + path);
      }
      totalBytes += decoded.data().length;
      if (totalBytes > maximumBytes) {
        throw new EditorSyncProjectTooLargeException(totalBytes, maximumBytes);
      }
      EditorSyncSnapshotBuilder.ValidatedImage image = workspace
          ? validateWorkspaceImage(path, decoded.mediaType(), decoded.data())
          : validateImage(path, decoded.mediaType(), decoded.data());
      validated.put(path, image);
      images.put(path, decoded.data());
    }
    EditorSyncSnapshotBuilder.validateWorkspaceImageBudgets(validated);
    return Map.copyOf(images);
  }

  private void enforceImageScope(EditorSyncStoredSession session,
                                 Map<String, byte[]> base,
                                 Map<String, byte[]> published) {
    if (session.kind() == EditorSyncKind.WORKSPACE) {
      return;
    }
    if (!published.keySet().containsAll(base.keySet())) {
      throw new IllegalArgumentException("individual sync sessions cannot delete images");
    }
    if (session.kind() != EditorSyncKind.MENU && session.kind() != EditorSyncKind.PANEL) {
      if (!published.isEmpty()) {
        throw new IllegalArgumentException("this individual subject cannot publish images");
      }
      return;
    }
    JsonObject constraints = EditorSyncJson.requireObject(session.baseProject(), "constraints");
    String prefix = EditorSyncJson.requireString(constraints, "newImagePrefix");
    for (String path : published.keySet()) {
      if (!base.containsKey(path) && !path.startsWith(prefix)) {
        throw new IllegalArgumentException("new image is outside the session prefix: " + path);
      }
    }
  }

  private void validateReferences(EditorSyncStoredSession session,
                                  Map<String, byte[]> baseImages,
                                  Map<DocumentKey, ParsedEntry> documents,
                                  Map<String, byte[]> images) {
    Set<String> referenced = new HashSet<>();
    List<String> menuSources = new ArrayList<>();
    for (Map.Entry<DocumentKey, ParsedEntry> document : documents.entrySet()) {
      if (document.getKey().kind() == EditorSyncDocumentKind.MENU) {
        String source = document.getValue().entry().json();
        menuSources.add(source);
        EditorSyncSnapshotBuilder.collectImagePaths(JsonParser.parseString(source), referenced);
      }
    }
    Map<String, EditorSyncSnapshotBuilder.ValidatedImage> validatedImages = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> image : images.entrySet()) {
      validatedImages.put(image.getKey(),
          EditorSyncSnapshotBuilder.validateWorkspaceImage(image.getKey(), image.getValue()));
    }
    EditorSyncSnapshotBuilder.validateReferencedImageBudgets(menuSources, validatedImages);
    if (session.kind() == EditorSyncKind.WORKSPACE) {
      return;
    }
    for (String path : images.keySet()) {
      if (!referenced.contains(path) && !baseImages.containsKey(path)) {
        throw new IllegalArgumentException("new unreferenced image is not allowed: " + path);
      }
    }
  }

  private Set<String> reachableMenus(PanelDefinition panel,
                                     Map<DocumentKey, ParsedEntry> documents) {
    Map<String, String> menus = new HashMap<>();
    for (Map.Entry<DocumentKey, ParsedEntry> entry : documents.entrySet()) {
      if (entry.getKey().kind() == EditorSyncDocumentKind.MENU) {
        menus.put(entry.getKey().id(), entry.getValue().entry().json());
      }
    }
    ArrayDeque<String> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.add(panel.rootMenuId());
    while (!pending.isEmpty()) {
      String id = pending.removeFirst();
      if (!visited.add(id)) {
        continue;
      }
      if (visited.size() > EditorSyncSnapshotBuilder.MAX_MENU_COUNT) {
        throw new IllegalArgumentException("panel menu graph exceeds "
            + EditorSyncSnapshotBuilder.MAX_MENU_COUNT + " menus");
      }
      String source = menus.get(id);
      if (source == null) {
        continue;
      }
      Set<String> targets = new TreeSet<>();
      EditorSyncSnapshotBuilder.collectTargets(JsonParser.parseString(source), targets);
      pending.addAll(targets);
    }
    return Set.copyOf(visited);
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

  private Set<EditorSyncDocumentKind> changedKinds(
      Map<DocumentKey, ParsedEntry> base,
      Map<DocumentKey, ParsedEntry> applied) {
    Set<EditorSyncDocumentKind> changed = new LinkedHashSet<>();
    Set<DocumentKey> keys = new HashSet<>(base.keySet());
    keys.addAll(applied.keySet());
    for (DocumentKey key : keys) {
      ParsedEntry previous = base.get(key);
      ParsedEntry current = applied.get(key);
      if (previous == null || current == null
          || !sameSource(previous.entry().json(), current.entry().json())) {
        changed.add(key.kind());
      }
    }
    return Set.copyOf(changed);
  }

  private DecodedImage decodeDataUrl(String value) {
    if (!value.startsWith("data:")) {
      throw new IllegalArgumentException("image data must be a data URL");
    }
    int separator = value.indexOf(',');
    if (separator < 6 || separator >= value.length() - 1) {
      throw new IllegalArgumentException("image data URL is malformed");
    }
    String descriptor = value.substring(5, separator);
    String[] parts = descriptor.split(";", -1);
    String mediaType = parts[0].toLowerCase(Locale.ROOT);
    if (!ACCEPTED_MEDIA_TYPES.contains(mediaType) || parts.length != 2
        || !parts[1].equalsIgnoreCase("base64")) {
      throw new IllegalArgumentException("image data URL must use an accepted base64 media type");
    }
    try {
      return new DecodedImage(mediaType,
          Base64.getDecoder().decode(value.substring(separator + 1)));
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("image data URL contains invalid base64", failure);
    }
  }

  private EditorSyncSnapshotBuilder.ValidatedImage validateImage(
      String path, String declaredMediaType, byte[] data) {
    String detected = EditorSyncSnapshotBuilder.detectMediaType(data);
    if (!detected.equals(declaredMediaType)) {
      throw new IllegalArgumentException("image media type does not match its bytes: " + path);
    }
    return EditorSyncSnapshotBuilder.validateImage(path, data);
  }

  private EditorSyncSnapshotBuilder.ValidatedImage validateWorkspaceImage(
      String path, String declaredMediaType, byte[] data) {
    String detected = EditorSyncSnapshotBuilder.detectMediaType(data);
    if (!detected.equals(declaredMediaType)) {
      throw new IllegalArgumentException("image media type does not match its bytes: " + path);
    }
    return EditorSyncSnapshotBuilder.validateWorkspaceImage(path, data);
  }

  private JsonObject panelJson(JsonObject project, String id) {
    for (EditorSyncDocuments.Entry entry : EditorSyncDocuments.parse(project)) {
      if (entry.kind().equals(EditorSyncDocumentKind.PANEL.wireName()) && entry.id().equals(id)) {
        JsonElement parsed = JsonParser.parseString(entry.json());
        if (!parsed.isJsonObject()) {
          throw new IllegalArgumentException("panel document must be a JSON object: " + id);
        }
        return parsed.getAsJsonObject();
      }
    }
    throw new IllegalArgumentException("sync project is missing its panel document");
  }

  private List<String> sortedUniqueKinds(JsonArray array, String label) {
    List<String> values = new ArrayList<>(array.size());
    String previous = null;
    for (JsonElement value : array) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException(label + " must contain strings");
      }
      String wireName = value.getAsString();
      EditorSyncDocumentKind.parseWireName(wireName);
      if (previous != null && previous.compareTo(wireName) >= 0) {
        throw new IllegalArgumentException(label + " must be sorted and unique");
      }
      previous = wireName;
      values.add(wireName);
    }
    return List.copyOf(values);
  }

  private boolean requireBoolean(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException(field + " must be a boolean");
    }
    return value.getAsBoolean();
  }

  private void requireExactKeys(JsonObject object, Set<String> expected, String label) {
    if (!object.keySet().equals(expected)) {
      throw new IllegalArgumentException(label + " contains missing or unsupported fields");
    }
  }

  private static boolean sameVersionedContent(String left, String right) {
    JsonObject leftObject = JsonParser.parseString(left).getAsJsonObject().deepCopy();
    JsonObject rightObject = JsonParser.parseString(right).getAsJsonObject().deepCopy();
    leftObject.remove("revision");
    rightObject.remove("revision");
    return EditorSyncJson.canonical(leftObject).equals(EditorSyncJson.canonical(rightObject));
  }

  private static boolean sameSource(String left, String right) {
    return stripLineEnd(left).equals(stripLineEnd(right));
  }

  private static String stripLineEnd(String source) {
    int end = source.length();
    while (end > 0 && (source.charAt(end - 1) == '\n' || source.charAt(end - 1) == '\r')) {
      end--;
    }
    return source.substring(0, end);
  }

  private static String ensureLineEnd(String source) {
    return source.endsWith("\n") || source.endsWith("\r")
        ? source
        : source + System.lineSeparator();
  }

  private static boolean sameImages(Map<String, byte[]> left, Map<String, byte[]> right) {
    if (!left.keySet().equals(right.keySet())) {
      return false;
    }
    for (String path : left.keySet()) {
      if (!Arrays.equals(left.get(path), right.get(path))) {
        return false;
      }
    }
    return true;
  }

  record DocumentKey(EditorSyncDocumentKind kind, String id) {
    DocumentKey {
      kind = Objects.requireNonNull(kind, "kind");
      id = kind.canonicalId(id);
    }
  }

  record ParsedEntry(EditorSyncDocuments.Entry entry, Object value) {
    ParsedEntry {
      entry = Objects.requireNonNull(entry, "entry");
      value = Objects.requireNonNull(value, "value");
    }
  }

  record ValidatedProject(EditorSyncProject project,
                          Map<DocumentKey, ParsedEntry> baseDocuments,
                          Map<DocumentKey, ParsedEntry> appliedDocuments,
                          Map<String, byte[]> baseImages,
                          Map<String, byte[]> appliedImages,
                          Set<EditorSyncDocumentKind> changedKinds,
                          boolean imagesChanged) {
    ValidatedProject {
      project = Objects.requireNonNull(project, "project");
      baseDocuments = Map.copyOf(baseDocuments);
      appliedDocuments = Map.copyOf(appliedDocuments);
      baseImages = copyImages(baseImages);
      appliedImages = copyImages(appliedImages);
      changedKinds = Set.copyOf(changedKinds);
    }

    boolean noOp() {
      return changedKinds.isEmpty() && !imagesChanged;
    }

    Map<String, String> appliedMenus() {
      Map<String, String> menus = new TreeMap<>();
      for (Map.Entry<DocumentKey, ParsedEntry> entry : appliedDocuments.entrySet()) {
        if (entry.getKey().kind() == EditorSyncDocumentKind.MENU) {
          menus.put(entry.getKey().id(), entry.getValue().entry().json());
        }
      }
      return Map.copyOf(menus);
    }

    Set<String> deletedMenus() {
      Set<String> deleted = new TreeSet<>();
      for (DocumentKey key : baseDocuments.keySet()) {
        if (key.kind() == EditorSyncDocumentKind.MENU && !appliedDocuments.containsKey(key)) {
          deleted.add(key.id());
        }
      }
      return Set.copyOf(deleted);
    }

    private static Map<String, byte[]> copyImages(Map<String, byte[]> images) {
      Map<String, byte[]> copied = new LinkedHashMap<>();
      images.forEach((path, content) -> copied.put(path, content.clone()));
      return Map.copyOf(copied);
    }
  }

  private record DecodedImage(String mediaType, byte[] data) {
  }
}
