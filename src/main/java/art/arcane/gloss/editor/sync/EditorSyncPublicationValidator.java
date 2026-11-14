package art.arcane.gloss.editor.sync;

import art.arcane.gloss.doc.DocumentPresetCatalog;
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

  ValidatedProject validate(Base session, EditorSyncPublication publication,
                            int maximumBytes, JsonObject serverProject) {
    if (!session.baseRevision().equals(publication.baseRevision())) {
      throw new IllegalArgumentException("publication baseRevision does not match the session base");
    }
    EditorSyncProject published = EditorSyncProject.validated(publication.snapshot(), maximumBytes);
    validateTopLevel(published);
    requireImmutableConstraints(session, published.json());
    requireEchoedUnhandledKinds(session.baseProject(), published.json());
    Map<DocumentKey, ParsedEntry> serverDocuments = parseDocuments(serverProject);
    Map<DocumentKey, ParsedEntry> sessionDocuments = parseDocuments(session.baseProject());
    Map<DocumentKey, ParsedEntry> publishedDocuments = parseDocuments(published.json());
    enforceDocumentKinds(publishedDocuments);
    Reconciliation reconciled = applyServerRevisions(serverDocuments, sessionDocuments,
        publishedDocuments, presets(published.json()));
    Map<DocumentKey, ParsedEntry> appliedDocuments = prepareApplied(reconciled.applied());
    validatePanels(serverDocuments, appliedDocuments);
    Map<String, byte[]> baseImages = parseImages(serverProject, maximumBytes);
    Map<String, byte[]> publishedImages = parseImages(published.json(), maximumBytes);
    validateReferences(appliedDocuments, publishedImages);

    List<EditorSyncDocuments.Entry> appliedEntries = appliedDocuments.values().stream()
        .map(ParsedEntry::entry)
        .sorted(Comparator.comparing(EditorSyncDocuments.Entry::kind)
            .thenComparing(EditorSyncDocuments.Entry::id))
        .toList();
    JsonObject constraints = EditorSyncJson.requireObject(session.baseProject(), "constraints");
    List<String> appliedWarnings = new ArrayList<>(structuredWarnings(serverProject));
    appliedWarnings.addAll(warnings(appliedEntries));
    EditorSyncProject appliedProject = EditorSyncContentSnapshotBuilder.project(
        appliedEntries, publishedImages, constraints,
        new EditorSyncProjectSections(appliedWarnings, serverSection(serverProject, "history",
                new JsonArray()).getAsJsonArray(),
            serverSection(serverProject, "schemas", new JsonObject()).getAsJsonObject(),
            serverSection(serverProject, "defaults", new JsonObject()).getAsJsonObject()),
        maximumBytes);
    Set<EditorSyncDocumentKind> changedKinds = changedKinds(serverDocuments, appliedDocuments);
    boolean imagesChanged = !sameImages(baseImages, publishedImages);
    return new ValidatedProject(appliedProject, serverDocuments, appliedDocuments,
        baseImages, publishedImages, changedKinds, imagesChanged, reconciled.conflicts());
  }

  /**
   * The project a publication is judged against: the workspace the changeset was merged onto
   * (constraints and unhandled kinds come from it).
   */
  record Base(JsonObject baseProject) {
    Base {
      Objects.requireNonNull(baseProject, "baseProject");
    }

    static Base of(EditorSyncProject project) {
      return new Base(project.json());
    }

    String baseRevision() {
      return EditorSyncJson.requireString(baseProject, "baseRevision");
    }
  }

  private void validateTopLevel(EditorSyncProject project) {
    Set<String> required = Set.of("format", "version", "kind", "subjectId",
        "documents", "images", "constraints", "warnings", "baseRevision");
    Set<String> optional = Set.of("history", "schemas", "defaults");
    if (!project.json().keySet().containsAll(required)
        || !optional.containsAll(difference(project.json().keySet(), required))) {
      throw new IllegalArgumentException("sync project contains missing or unsupported fields");
    }
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

  private static Set<String> difference(Set<String> present, Set<String> known) {
    Set<String> extra = new LinkedHashSet<>(present);
    extra.removeAll(known);
    return extra;
  }

  private void requireImmutableConstraints(Base session, JsonObject project) {
    JsonObject stored = EditorSyncJson.requireObject(session.baseProject(), "constraints");
    JsonObject published = EditorSyncJson.requireObject(project, "constraints");
    if (!EditorSyncJson.canonical(stored).equals(EditorSyncJson.canonical(published))) {
      throw new IllegalArgumentException("sync publication cannot change its capability constraints");
    }
    validateConstraintShape(stored);
  }

  private void validateConstraintShape(JsonObject constraints) {
    requireExactKeys(constraints, Set.of("subjectId", "documentKinds", "createDocumentKinds", "allowDeletes"),
        "sync constraints");
    if (!EditorSyncContentSnapshotBuilder.WORKSPACE_SUBJECT_ID.equals(
        EditorSyncJson.requireString(constraints, "subjectId"))) {
      throw new IllegalArgumentException("sync constraints do not name the workspace");
    }
    List<String> documentKinds = sortedUniqueKinds(
        EditorSyncJson.requireArray(constraints, "documentKinds"), "documentKinds");
    List<String> createKinds = sortedUniqueKinds(
        EditorSyncJson.requireArray(constraints, "createDocumentKinds"), "createDocumentKinds");
    if (!requireBoolean(constraints, "allowDeletes")) {
      throw new IllegalArgumentException("workspace sync constraints must allow deletes");
    }
    if (!documentKinds.containsAll(EditorSyncDocumentKind.ORDERED_WIRE_NAMES)
        || !createKinds.containsAll(EditorSyncDocumentKind.ORDERED_WIRE_NAMES)) {
      throw new IllegalArgumentException("sync constraint document kinds are invalid");
    }
  }

  /**
   * A kind this build does not handle may only travel back exactly as it was served. The editor
   * mirrors it byte for byte; anything else names the kind and refuses, so a document the server
   * cannot parse is never quietly dropped or overwritten.
   */
  private void requireEchoedUnhandledKinds(JsonObject sessionProject, JsonObject published) {
    Map<String, String> served = unhandledDocuments(sessionProject);
    for (Map.Entry<String, String> entry : unhandledDocuments(published).entrySet()) {
      if (!entry.getValue().equals(served.get(entry.getKey()))) {
        throw new IllegalArgumentException(
            "sync document kind is not handled by this server: " + entry.getKey());
      }
    }
  }

  private Map<String, String> unhandledDocuments(JsonObject project) {
    Map<String, String> documents = new LinkedHashMap<>();
    for (EditorSyncDocuments.Entry entry : EditorSyncDocuments.parse(project)) {
      if (EditorSyncDocuments.handledKind(entry.kind()) == null) {
        documents.put(entry.kind() + " " + entry.id(), entry.json());
      }
    }
    return Map.copyOf(documents);
  }

  private Map<DocumentKey, ParsedEntry> parseDocuments(JsonObject project) {
    Map<DocumentKey, ParsedEntry> documents = new LinkedHashMap<>();
    DocumentPresetCatalog presets = presets(project);
    for (EditorSyncDocuments.Entry entry : EditorSyncDocuments.parse(project)) {
      if (EditorSyncDocuments.handledKind(entry.kind()) == null) {
        continue;
      }
      EditorSyncDocumentKind kind = EditorSyncDocumentKind.parseWireName(entry.kind());
      EditorSyncDocumentKind.ParsedDocument parsed = kind.parse(entry.id(), entry.json(), presets);
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

  private void enforceDocumentKinds(Map<DocumentKey, ParsedEntry> published) {
    for (DocumentKey key : published.keySet()) {
      if (!EditorSyncDocumentKind.ORDERED_WIRE_NAMES.contains(key.kind().wireName())) {
        throw new IllegalArgumentException("document kind is not handled by this server: "
            + key.kind().wireName());
      }
    }
  }

  static DocumentPresetCatalog presets(JsonObject project) {
    for (EditorSyncDocuments.Entry entry : EditorSyncDocuments.parse(project)) {
      if (entry.kind().equals(EditorSyncDocumentKind.PRESETS.wireName())) {
        return DocumentPresetCatalog.parse(DocumentPresetCatalog.FILE_NAME, entry.json());
      }
    }
    JsonObject constraints = EditorSyncJson.requireObject(project, "constraints");
    return constraints.has("presets")
        ? DocumentPresetCatalog.parse(DocumentPresetCatalog.FILE_NAME, constraints.get("presets").toString())
        : DocumentPresetCatalog.empty();
  }

  private Map<DocumentKey, ParsedEntry> prepareApplied(Map<DocumentKey, ParsedEntry> entries) {
    ParsedEntry catalog = entries.get(new DocumentKey(EditorSyncDocumentKind.PRESETS, "presets"));
    DocumentPresetCatalog presets = catalog == null ? DocumentPresetCatalog.empty()
        : DocumentPresetCatalog.parse(DocumentPresetCatalog.FILE_NAME, catalog.entry().json());
    Map<DocumentKey, ParsedEntry> prepared = new LinkedHashMap<>();
    for (Map.Entry<DocumentKey, ParsedEntry> entry : entries.entrySet()) {
      DocumentKey key = entry.getKey();
      ParsedEntry value = entry.getValue();
      Object parsed = key.kind().parse(key.id(), value.entry().json(), presets).value();
      prepared.put(key, new ParsedEntry(value.entry(), parsed));
    }
    return Map.copyOf(prepared);
  }

  private Reconciliation applyServerRevisions(
      Map<DocumentKey, ParsedEntry> base,
      Map<DocumentKey, ParsedEntry> session,
      Map<DocumentKey, ParsedEntry> published, DocumentPresetCatalog presets) {
    Map<DocumentKey, ParsedEntry> applied = new LinkedHashMap<>();
    List<DocumentKey> conflicts = new ArrayList<>();
    for (Map.Entry<DocumentKey, ParsedEntry> publishedEntry : published.entrySet()) {
      DocumentKey key = publishedEntry.getKey();
      ParsedEntry incoming = publishedEntry.getValue();
      ParsedEntry previous = base.get(key);
      String editorBase = incoming.entry().baseRevision();
      String serverRevision = contentRevision(previous);
      if (editorBase != null && !editorBase.equals(serverRevision)) {
        String editorRevision = EditorSyncDocuments.contentRevision(incoming.entry().json());
        boolean editorChanged = !editorRevision.equals(editorBase);
        if (editorChanged && !editorRevision.equals(serverRevision)) {
          conflicts.add(key);
        }
        if (previous != null) {
          applied.put(key, previous);
        }
        continue;
      }
      if (previous == null) {
        if (key.kind().versioned() && incoming.entry().revision() != 1L) {
          throw new IllegalArgumentException("new versioned documents must start at revision 1: "
              + key.kind().wireName() + " " + key.id());
        }
        applied.put(key, normalizeNew(incoming, presets));
        continue;
      }
      if (!key.kind().versioned()) {
        if (sameSource(previous.entry().json(), incoming.entry().json())) {
          applied.put(key, previous);
          continue;
        }
        String edited = EditorSyncAuthoredSource.edit(previous.entry().json(),
            JsonParser.parseString(incoming.entry().json()));
        applied.put(key, edited == null ? normalizeNew(incoming, presets) : normalize(incoming, edited, presets));
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
      String edited = EditorSyncAuthoredSource.edit(previous.entry().json(), changed);
      String persistedShape = edited == null ? GSON.toJson(changed) + System.lineSeparator() : edited;
      EditorSyncDocumentKind.ParsedDocument parsed = key.kind().parse(key.id(), persistedShape, presets);
      String normalized = key.kind().wireSource(key.id(), persistedShape, parsed, presets);
      EditorSyncDocuments.Entry revised = new EditorSyncDocuments.Entry(
          key.kind().wireName(), key.id(), previousRevision + 1L, normalized,
          EditorSyncDocuments.contentRevision(normalized));
      applied.put(key, new ParsedEntry(revised, parsed.value()));
    }
    retainServerOnlyDocuments(base, session, published, applied, conflicts);
    conflicts.sort(Comparator.comparing((DocumentKey key) -> key.kind().wireName())
        .thenComparing(DocumentKey::id));
    return new Reconciliation(Map.copyOf(applied), List.copyOf(conflicts));
  }

  private void retainServerOnlyDocuments(Map<DocumentKey, ParsedEntry> base,
                                         Map<DocumentKey, ParsedEntry> session,
                                         Map<DocumentKey, ParsedEntry> published,
                                         Map<DocumentKey, ParsedEntry> applied,
                                         List<DocumentKey> conflicts) {
    for (Map.Entry<DocumentKey, ParsedEntry> serverEntry : base.entrySet()) {
      DocumentKey key = serverEntry.getKey();
      if (published.containsKey(key) || applied.containsKey(key)) {
        continue;
      }
      ParsedEntry sessionEntry = session.get(key);
      if (sessionEntry == null) {
        applied.put(key, serverEntry.getValue());
        continue;
      }
      if (!sameSource(sessionEntry.entry().json(), serverEntry.getValue().entry().json())) {
        conflicts.add(key);
        applied.put(key, serverEntry.getValue());
      }
    }
  }

  private static String contentRevision(ParsedEntry entry) {
    return entry == null ? null : EditorSyncDocuments.contentRevision(entry.entry().json());
  }

  private ParsedEntry normalizeNew(ParsedEntry incoming, DocumentPresetCatalog presets) {
    return normalize(incoming, ensureLineEnd(incoming.entry().json()), presets);
  }

  /** {@code incoming} as it will be written: {@code persistedShape}, checked by the kind's parser. */
  private ParsedEntry normalize(ParsedEntry incoming, String persistedShape, DocumentPresetCatalog presets) {
    EditorSyncDocumentKind kind = EditorSyncDocumentKind.parseWireName(incoming.entry().kind());
    EditorSyncDocumentKind.ParsedDocument parsed = kind.parse(incoming.entry().id(), persistedShape, presets);
    String source = kind.wireSource(incoming.entry().id(), persistedShape, parsed, presets);
    if (source.equals(incoming.entry().json())) {
      return incoming;
    }
    EditorSyncDocuments.Entry normalized = new EditorSyncDocuments.Entry(
        incoming.entry().kind(), incoming.entry().id(), incoming.entry().revision(), source,
        EditorSyncDocuments.contentRevision(source));
    return new ParsedEntry(normalized, parsed.value());
  }

  private void validatePanels(Map<DocumentKey, ParsedEntry> base,
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
  }

  private Map<String, byte[]> parseImages(JsonObject project, int maximumBytes) {
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
      EditorSyncSnapshotBuilder.ValidatedImage image =
          validateWorkspaceImage(path, decoded.mediaType(), decoded.data());
      validated.put(path, image);
      images.put(path, decoded.data());
    }
    EditorSyncSnapshotBuilder.validateWorkspaceImageBudgets(validated);
    return Map.copyOf(images);
  }

  private void validateReferences(Map<DocumentKey, ParsedEntry> documents,
                                  Map<String, byte[]> images) {
    List<String> menuSources = new ArrayList<>();
    for (Map.Entry<DocumentKey, ParsedEntry> document : documents.entrySet()) {
      if (document.getKey().kind() == EditorSyncDocumentKind.MENU) {
        menuSources.add(document.getValue().entry().json());
      }
    }
    Map<String, EditorSyncSnapshotBuilder.ValidatedImage> validatedImages = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> image : images.entrySet()) {
      validatedImages.put(image.getKey(),
          EditorSyncSnapshotBuilder.validateWorkspaceImage(image.getKey(), image.getValue()));
    }
    EditorSyncSnapshotBuilder.validateReferencedImageBudgets(menuSources, validatedImages);
  }

  /**
   * Diagnostics the server computed about files the project does not carry, in the
   * {@code code|kind|id|pointer|message} shape. They survive a publication unchanged; the
   * document-derived warnings beside them are recomputed from what was applied.
   */
  private static JsonElement serverSection(JsonObject project, String field, JsonElement fallback) {
    JsonElement value = project.get(field);
    return value == null || value.isJsonNull() ? fallback : value;
  }

  private List<String> structuredWarnings(JsonObject project) {
    List<String> warnings = new ArrayList<>();
    for (JsonElement warning : EditorSyncJson.requireArray(project, "warnings")) {
      String value = warning.getAsString();
      if (value.indexOf('|') >= 0) {
        warnings.add(value);
      }
    }
    return List.copyOf(warnings);
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

  private EditorSyncSnapshotBuilder.ValidatedImage validateWorkspaceImage(
      String path, String declaredMediaType, byte[] data) {
    String detected = EditorSyncSnapshotBuilder.detectMediaType(data);
    if (!detected.equals(declaredMediaType)) {
      throw new IllegalArgumentException("image media type does not match its bytes: " + path);
    }
    return EditorSyncSnapshotBuilder.validateWorkspaceImage(path, data);
  }

  private List<String> sortedUniqueKinds(JsonArray array, String label) {
    List<String> values = new ArrayList<>(array.size());
    String previous = null;
    for (JsonElement value : array) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException(label + " must contain strings");
      }
      String wireName = value.getAsString();
      if (!EditorSyncDocuments.WIRE_KIND_PATTERN.matcher(wireName).matches()) {
        throw new IllegalArgumentException(label + " must contain sync v3 slugs");
      }
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

  record Reconciliation(Map<DocumentKey, ParsedEntry> applied, List<DocumentKey> conflicts) {
    Reconciliation {
      applied = Map.copyOf(applied);
      conflicts = List.copyOf(conflicts);
    }
  }

  record ValidatedProject(EditorSyncProject project,
                          Map<DocumentKey, ParsedEntry> baseDocuments,
                          Map<DocumentKey, ParsedEntry> appliedDocuments,
                          Map<String, byte[]> baseImages,
                          Map<String, byte[]> appliedImages,
                          Set<EditorSyncDocumentKind> changedKinds,
                          boolean imagesChanged,
                          List<DocumentKey> conflicts) {
    ValidatedProject {
      project = Objects.requireNonNull(project, "project");
      baseDocuments = Map.copyOf(baseDocuments);
      appliedDocuments = Map.copyOf(appliedDocuments);
      baseImages = copyImages(baseImages);
      appliedImages = copyImages(appliedImages);
      changedKinds = Set.copyOf(changedKinds);
      conflicts = List.copyOf(conflicts);
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
