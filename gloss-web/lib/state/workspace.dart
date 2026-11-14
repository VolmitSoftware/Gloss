library;

import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:jaspr/jaspr.dart' show ChangeNotifier;

import '../config/defaults.dart';
import '../l10n/hui_localizations.dart';
import '../services/storage_service.dart';
import 'workspace_records.dart';
import 'workspace_repository.dart';

typedef StorageReader = String? Function(String key);
typedef StorageWriter = bool Function(String key, String value);
typedef WorkspaceIdFactory = String Function();

String? defaultStorageRead(String key) => StorageService.read(key);

bool defaultStorageWrite(String key, String value) =>
    StorageService.write(key, value);

enum WorkspaceDocKind {
  menu,
  containerPreview,
  panel,
  hologram,
  animation,
  scoreboard,
  surface,
  motd,
  connections,
  channel,
  emoji,
  bubbleStyle,
  damageIndicators,
  entityOverlays,
  tablist,
  realDrops,
  inventory,
  nameplate,
  nametag,
  marker,
  names,
  strings,
  waypoint,
  glyph,
  behavior,
  presets,
  leaderboard;

  bool get hasRuntimeId => this != WorkspaceDocKind.panel;

  /// World panels were stored as `board` before the Gloss rename. Stored
  /// documents carrying the old slug must keep loading forever, so the legacy
  /// name maps here rather than being migrated in place.
  static const String _legacyPanelName = 'board';

  static WorkspaceDocKind? fromName(Object? raw) {
    if (raw == _legacyPanelName) return WorkspaceDocKind.panel;
    for (final WorkspaceDocKind value in values) {
      if (value.name == raw) return value;
    }
    return null;
  }
}

class WorkspaceFolder {
  WorkspaceFolder({
    required this.id,
    required this.title,
    required this.updatedAt,
    this.parentId,
  });

  final String id;
  String title;
  String? parentId;
  int updatedAt;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'id': id,
    'title': title,
    'parentId': parentId,
    'updatedAt': updatedAt,
  };

  static WorkspaceFolder? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final Object? id = raw['id'];
    if (id is! String || !isWorkspaceUuid(id)) return null;
    final Object? title = raw['title'];
    final Object? parentId = raw['parentId'];
    final Object? updatedAt = raw['updatedAt'];
    if (parentId != null &&
        (parentId is! String || !isWorkspaceUuid(parentId))) {
      return null;
    }
    return WorkspaceFolder(
      id: id,
      title: normalizeWorkspaceTitle(title, fallback: huiText('Folder')),
      parentId: parentId as String?,
      updatedAt: updatedAt is num ? updatedAt.toInt() : 0,
    );
  }
}

class WorkspaceDoc {
  WorkspaceDoc({
    required this.id,
    required this.title,
    required this.runtimeId,
    required this.json,
    required this.updatedAt,
    required this.folderId,
    this.kind = WorkspaceDocKind.menu,
    this.presetContext,
  });

  final String id;
  String title;
  String? runtimeId;
  String json;
  WorkspaceDocKind kind;
  String? folderId;
  int updatedAt;

  String? presetContext;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'id': id,
    'title': title,
    'runtimeId': runtimeId,
    'json': json,
    'updatedAt': updatedAt,
    'kind': kind.name,
    'folderId': folderId,
    if (presetContext != null) 'presetContext': presetContext,
  };

  static WorkspaceDoc? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final Object? id = raw['id'];
    final Object? json = raw['json'];
    final WorkspaceDocKind? kind = WorkspaceDocKind.fromName(raw['kind']);
    final Object? folderId = raw['folderId'];
    if (id is! String || !isWorkspaceUuid(id)) return null;
    if (json is! String || json.isEmpty || !_isJson(json)) return null;
    if (kind == null) return null;
    if (folderId != null &&
        (folderId is! String || !isWorkspaceUuid(folderId))) {
      return null;
    }

    String? runtimeId;
    if (kind.hasRuntimeId) {
      final Object? rawRuntimeId = raw['runtimeId'];
      if (rawRuntimeId is! String || validateMenuId(rawRuntimeId) != null) {
        return null;
      }
      runtimeId = rawRuntimeId;
    }
    final Object? updatedAt = raw['updatedAt'];
    return WorkspaceDoc(
      id: id,
      title: normalizeWorkspaceTitle(
        raw['title'],
        fallback: runtimeId ?? huiText('Untitled panel'),
      ),
      runtimeId: runtimeId,
      json: json,
      updatedAt: updatedAt is num ? updatedAt.toInt() : 0,
      kind: kind,
      folderId: folderId as String?,
      presetContext: raw['presetContext'] is String ? raw['presetContext'] as String : null,
    );
  }
}

class WorkspaceDocumentUpdate {
  const WorkspaceDocumentUpdate({
    required this.documentId,
    this.runtimeId,
    this.json,
  });

  final String documentId;
  final String? runtimeId;
  final String? json;
}

class WorkspaceRuntimeIdConflict {
  const WorkspaceRuntimeIdConflict({
    required this.kind,
    required this.runtimeId,
    required this.documents,
  });

  final WorkspaceDocKind kind;
  final String runtimeId;
  final List<WorkspaceDoc> documents;
}

typedef _WorkspaceMessage = String Function();

_WorkspaceMessage _workspaceText(
  String english, [
  Map<String, Object?> arguments = const <String, Object?>{},
]) {
  final Map<String, Object?> captured = Map<String, Object?>.of(arguments);
  return () => huiText(english, captured);
}

_WorkspaceMessage _workspacePlural(
  String key,
  int count, {
  required String oneEnglish,
  required String otherEnglish,
}) =>
    () => huiPlural(
      key,
      count,
      oneEnglish: oneEnglish,
      otherEnglish: otherEnglish,
    );

class WorkspacePortableStateCheck {
  WorkspacePortableStateCheck({
    required this.isValid,
    required this.documentCount,
    required this.folderCount,
    String? warning,
    String? error,
  }) : _warning = warning == null ? null : _workspaceText(warning),
       _error = error == null ? null : _workspaceText(error);

  const WorkspacePortableStateCheck._messages({
    required this.isValid,
    required this.documentCount,
    required this.folderCount,
    _WorkspaceMessage? warning,
    _WorkspaceMessage? error,
  }) : _warning = warning,
       _error = error;

  final bool isValid;
  final int documentCount;
  final int folderCount;
  final _WorkspaceMessage? _warning;
  final _WorkspaceMessage? _error;

  String? get warning => _warning?.call();

  String? get error => _error?.call();
}

class Workspace extends ChangeNotifier {
  Workspace({
    this.read = defaultStorageRead,
    this.write = defaultStorageWrite,
    WorkspaceRepository? repository,
    WorkspaceIdFactory idFactory = newWorkspaceUuid,
    bool autoLoad = true,
    this.key = storageKey,
  }) : _idFactory = idFactory {
    this.repository =
        repository ??
        CallbackWorkspaceMaintenanceRepository(
          reader: read,
          writer: write,
          clearer: () {
            final bool current = StorageService.remove(storageKey);
            final bool legacy = StorageService.remove(legacyStorageKey);
            StorageService.remove(preRebrandStorageKey);
            StorageService.remove(preRebrandLegacyStorageKey);
            return current && legacy;
          },
        );
    final WorkspaceRepository source = this.repository;
    if (source is ObservableWorkspaceRepository) {
      final ObservableWorkspaceRepository observable =
          source as ObservableWorkspaceRepository;
      observable.setNoticeListener(_handleRepositoryNotice);
    }
    _resetToEmpty();
    if (autoLoad) {
      _ready = _load().then((void _) => _takeRescue());
    } else {
      _isReady = true;
      _ready = Future<void>.value();
    }
  }

  static const int schemaVersion = wholeWorkspaceSchema;

  /// The browser workspace's record.
  static const String storageKey = 'gloss.workspace.v2';
  static const String legacyStorageKey = 'gloss.workspace.v1';

  /// The record of a paired server's workspace.
  static String serverStorageKey(String serverId) =>
      'gloss.workspace.server.$serverId';

  /// Pre-rebrand key names. [StorageService] migrates the localStorage copies
  /// and the IndexedDB repository migrates its records, but explicit clears
  /// must also drop these so old data cannot re-migrate after a reset.
  static const String preRebrandStorageKey = 'holoui.workspace.v2';
  static const String preRebrandLegacyStorageKey = 'holoui.workspace.v1';

  final StorageReader read;
  final StorageWriter write;

  /// The record this workspace lives in: [storageKey] for the browser
  /// workspace, [serverStorageKey] for a paired server's.
  final String key;
  late final WorkspaceRepository repository;
  final WorkspaceIdFactory _idFactory;

  final List<WorkspaceDoc> _docs = <WorkspaceDoc>[];
  final List<WorkspaceFolder> _folders = <WorkspaceFolder>[];
  late String _workspaceId;
  String? _activeId;
  _WorkspaceMessage? _lastError;
  bool _quotaExceeded = false;
  bool _isReady = false;
  int _lastStamp = 0;
  int _saveRevision = 0;
  int _persistedRevision = 0;

  /// The revision of the workspace storage holds, read back at load; every
  /// write carries the next one.
  int _storedRevision = 0;
  int _issuedRevision = 0;

  /// What a layered store holds, as far as this tab wrote or read it; a save
  /// writes only what differs. Unset until storage is known to match.
  _StoredLayout? _stored;

  bool _loadProtected = false;
  bool _requiresReload = false;
  Future<void>? _writeTail;
  late Future<void> _ready;

  static Future<Workspace> open({
    required WorkspaceRepository repository,
    StorageReader read = defaultStorageRead,
    StorageWriter write = defaultStorageWrite,
    WorkspaceIdFactory idFactory = newWorkspaceUuid,
    String key = storageKey,
  }) async {
    final Workspace workspace = Workspace(
      read: read,
      write: write,
      repository: repository,
      idFactory: idFactory,
      autoLoad: false,
      key: key,
    );
    await workspace.load();
    return workspace;
  }

  String get id => _workspaceId;

  Future<void> get ready => _ready;

  bool get isReady => _isReady;

  List<WorkspaceDoc> get docs => List<WorkspaceDoc>.unmodifiable(_docs);

  List<WorkspaceFolder> get folders =>
      List<WorkspaceFolder>.unmodifiable(_folders);

  List<WorkspaceDoc> get recent {
    final List<WorkspaceDoc> sorted = List<WorkspaceDoc>.of(_docs);
    sorted.sort(
      (WorkspaceDoc a, WorkspaceDoc b) => b.updatedAt.compareTo(a.updatedAt),
    );
    return sorted;
  }

  String? get activeId => _activeId;

  WorkspaceDoc? get active => byId(_activeId);

  bool get isEmpty => _docs.isEmpty;

  String? get lastError => _lastError?.call();

  String Function()? get lastErrorMessage => _lastError;

  bool get quotaExceeded => _quotaExceeded;

  bool get hasPendingWrites => _writeTail != null;

  bool get hasUnsavedChanges => _persistedRevision < _saveRevision;

  Future<void> get writesSettled => _writeTail ?? Future<void>.value();

  bool get isLoadProtected => _loadProtected;

  bool get requiresReload => _requiresReload;

  bool get canWrite =>
      _isReady && !_loadProtected && !_requiresReload && !_writesPaused;

  /// Another tab of this browser holds this workspace: it is shown read
  /// only and nothing is written until this tab takes it over.
  bool get writesPaused => _writesPaused;
  bool _writesPaused = false;

  void pauseWrites() {
    if (_writesPaused) return;
    _writesPaused = true;
    // A change heard before this tab knew it waits was the holder's write;
    // this tab reads the workspace again when it takes over.
    if (_requiresReload) {
      _requiresReload = false;
      _quotaExceeded = false;
      _lastError = null;
    }
    notifyListeners();
  }

  int get saveRevision => _saveRevision;

  int get persistedRevision => _persistedRevision;

  /// Bumped on every notification, so per-render lookups can be cached.
  int _version = 0;

  /// Changes whenever the workspace notifies: a key for caches built from it.
  int get version => _version;
  int _conflictsVersion = -1;
  List<WorkspaceRuntimeIdConflict> _conflicts =
      const <WorkspaceRuntimeIdConflict>[];

  @override
  void notifyListeners() {
    _version++;
    super.notifyListeners();
  }

  /// Runtime ids shared by two documents of one kind. Cached until the
  /// workspace changes: the library asks once per row.
  List<WorkspaceRuntimeIdConflict> get runtimeIdConflicts {
    if (_conflictsVersion == _version) return _conflicts;
    _conflicts = _computeRuntimeIdConflicts();
    _conflictsVersion = _version;
    return _conflicts;
  }

  List<WorkspaceRuntimeIdConflict> _computeRuntimeIdConflicts() {
    final Map<String, List<WorkspaceDoc>> grouped =
        <String, List<WorkspaceDoc>>{};
    for (final WorkspaceDoc doc in _docs) {
      final String? runtimeId = doc.runtimeId;
      if (!doc.kind.hasRuntimeId || runtimeId == null) continue;
      final String key = '${doc.kind.name}\u0000${runtimeId.toLowerCase()}';
      grouped.putIfAbsent(key, () => <WorkspaceDoc>[]).add(doc);
    }
    final List<WorkspaceRuntimeIdConflict> conflicts =
        <WorkspaceRuntimeIdConflict>[];
    for (final List<WorkspaceDoc> documents in grouped.values) {
      if (documents.length < 2) continue;
      conflicts.add(
        WorkspaceRuntimeIdConflict(
          kind: documents.first.kind,
          runtimeId: documents.first.runtimeId!,
          documents: List<WorkspaceDoc>.unmodifiable(documents),
        ),
      );
    }
    conflicts.sort(
      (WorkspaceRuntimeIdConflict a, WorkspaceRuntimeIdConflict b) =>
          a.runtimeId.compareTo(b.runtimeId),
    );
    return List<WorkspaceRuntimeIdConflict>.unmodifiable(conflicts);
  }

  WorkspaceDoc? byId(String? id) {
    if (id == null) return null;
    for (final WorkspaceDoc doc in _docs) {
      if (doc.id == id) return doc;
    }
    return null;
  }

  WorkspaceFolder? folderById(String? id) {
    if (id == null) return null;
    for (final WorkspaceFolder folder in _folders) {
      if (folder.id == id) return folder;
    }
    return null;
  }

  List<WorkspaceFolder> childFolders(String? parentId) =>
      List<WorkspaceFolder>.unmodifiable(
        _folders.where((WorkspaceFolder folder) => folder.parentId == parentId),
      );

  List<WorkspaceDoc> documentsInFolder(String? folderId) =>
      List<WorkspaceDoc>.unmodifiable(
        _docs.where((WorkspaceDoc doc) => doc.folderId == folderId),
      );

  Future<void> load() {
    _ready = _load().then((void _) => _takeRescue());
    return _ready;
  }

  /// Where [rescueOnExit] keeps the open document.
  String get _rescueKey => '$key.unsaved';

  /// Called as the page goes away. A browser storage write still on its way
  /// when the page unloads may never finish, so the open document is also
  /// written to localStorage at once; the next load takes it back.
  void rescueOnExit() {
    if (!hasUnsavedChanges && !hasPendingWrites) return;
    final WorkspaceDoc? doc = active;
    if (doc == null || !canWrite) return;
    write(
      _rescueKey,
      jsonEncode(<String, Object?>{
        'documentId': doc.id,
        'runtimeId': doc.runtimeId,
        'json': doc.json,
      }),
    );
  }

  /// Puts back a document [rescueOnExit] kept when the stored copy did not
  /// get the last write.
  void _takeRescue() {
    final String? raw = read(_rescueKey);
    if (raw == null || raw.isEmpty) return;
    write(_rescueKey, '');
    try {
      final Object? decoded = jsonDecode(raw);
      if (decoded is! Map) return;
      final Object? documentId = decoded['documentId'];
      final Object? json = decoded['json'];
      final Object? runtimeId = decoded['runtimeId'];
      if (documentId is! String || json is! String || !_isJson(json)) return;
      final WorkspaceDoc? doc = byId(documentId);
      if (doc == null || doc.json == json || !canWrite) return;
      updateDocuments(<WorkspaceDocumentUpdate>[
        WorkspaceDocumentUpdate(
          documentId: documentId,
          json: json,
          runtimeId: runtimeId is String && runtimeId != doc.runtimeId
              ? runtimeId
              : null,
        ),
      ]);
    } catch (_) {
      // A damaged rescue record is dropped; the stored workspace stands.
    }
  }

  WorkspaceDoc create({
    required String title,
    required String json,
    String? runtimeId,
    WorkspaceDocKind kind = WorkspaceDocKind.menu,
    String? folderId,
    String? presetContext,
  }) {
    _requireReady();
    _requireWritable();
    final String? targetFolder = folderById(folderId)?.id;
    final String? canonicalRuntimeId = kind.hasRuntimeId
        ? sanitizeMenuId(runtimeId ?? title)
        : null;
    final WorkspaceDoc doc = WorkspaceDoc(
      id: _newUniqueId(<String>{for (final WorkspaceDoc doc in _docs) doc.id}),
      title: normalizeWorkspaceTitle(
        title,
        fallback: canonicalRuntimeId ?? huiText('Untitled panel'),
      ),
      runtimeId: canonicalRuntimeId,
      json: json,
      updatedAt: _stamp(),
      kind: kind,
      folderId: targetFolder,
      presetContext: presetContext,
    );
    _docs.add(doc);
    _activeId = doc.id;
    save();
    notifyListeners();
    return doc;
  }

  bool renameDocumentTitle(String id, String title) {
    if (!canWrite) return false;
    final WorkspaceDoc? doc = byId(id);
    if (doc == null) return false;
    final String normalized = normalizeWorkspaceTitle(
      title,
      fallback: doc.runtimeId ?? huiText('Untitled panel'),
    );
    if (doc.title == normalized) return true;
    doc.title = normalized;
    doc.updatedAt = _stamp();
    save();
    notifyListeners();
    return true;
  }

  bool renameDocumentRuntimeId(String id, String runtimeId) {
    if (!canWrite) return false;
    final WorkspaceDoc? doc = byId(id);
    if (doc == null || !doc.kind.hasRuntimeId) return false;
    final String canonical = sanitizeMenuId(runtimeId);
    if (doc.runtimeId == canonical) return true;
    doc.runtimeId = canonical;
    doc.updatedAt = _stamp();
    save();
    notifyListeners();
    return true;
  }

  bool updateDocuments(List<WorkspaceDocumentUpdate> updates) {
    if (!canWrite || updates.isEmpty) return false;
    final Set<String> ids = <String>{};
    for (final WorkspaceDocumentUpdate update in updates) {
      if (!ids.add(update.documentId)) return false;
      final WorkspaceDoc? document = byId(update.documentId);
      if (document == null) return false;
      if (update.runtimeId != null && !document.kind.hasRuntimeId) return false;
      if (update.json != null && !_isJson(update.json!)) return false;
    }

    bool changed = false;
    for (final WorkspaceDocumentUpdate update in updates) {
      final WorkspaceDoc document = byId(update.documentId)!;
      bool documentChanged = false;
      if (update.runtimeId != null) {
        final String canonical = sanitizeMenuId(update.runtimeId!);
        if (canonical != document.runtimeId) {
          document.runtimeId = canonical;
          documentChanged = true;
        }
      }
      if (update.json != null && update.json != document.json) {
        document.json = update.json!;
        documentChanged = true;
      }
      if (documentChanged) {
        document.updatedAt = _stamp();
        changed = true;
      }
    }
    if (!changed) return true;
    final bool stored = save();
    notifyListeners();
    return stored;
  }

  /// Adds [folders] and [documents] (fresh ids, each folder existing or
  /// added here), applies [updates] and deletes [removals], all in one
  /// save. The live documents change in place, so an edit saved while the
  /// caller waited is never undone. False when the batch is invalid or the
  /// workspace cannot write.
  bool applyBatch({
    List<WorkspaceFolder> folders = const <WorkspaceFolder>[],
    List<WorkspaceDoc> documents = const <WorkspaceDoc>[],
    List<WorkspaceDocumentUpdate> updates = const <WorkspaceDocumentUpdate>[],
    Set<String> removals = const <String>{},
  }) {
    if (!canWrite) return false;
    if (folders.isEmpty &&
        documents.isEmpty &&
        updates.isEmpty &&
        removals.isEmpty) {
      return true;
    }
    final Set<String> used = <String>{
      for (final WorkspaceDoc doc in _docs) doc.id,
      for (final WorkspaceFolder folder in _folders) folder.id,
    };
    final Set<String> folderIds = <String>{
      for (final WorkspaceFolder folder in _folders) folder.id,
    };
    for (final WorkspaceFolder folder in folders) {
      if (!isWorkspaceUuid(folder.id) || !used.add(folder.id)) return false;
      if (folder.parentId != null && !folderIds.contains(folder.parentId)) {
        return false;
      }
      folderIds.add(folder.id);
    }
    for (final WorkspaceDoc doc in documents) {
      if (!isWorkspaceUuid(doc.id) || !used.add(doc.id) || !_isJson(doc.json)) {
        return false;
      }
      if (doc.folderId != null && !folderIds.contains(doc.folderId)) {
        return false;
      }
    }
    final Set<String> updated = <String>{};
    for (final WorkspaceDocumentUpdate update in updates) {
      if (!updated.add(update.documentId) ||
          removals.contains(update.documentId)) {
        return false;
      }
      final WorkspaceDoc? document = byId(update.documentId);
      if (document == null) return false;
      if (update.runtimeId != null && !document.kind.hasRuntimeId) return false;
      if (update.json != null && !_isJson(update.json!)) return false;
    }

    for (final WorkspaceFolder folder in folders) {
      folder.updatedAt = _stamp();
      _folders.add(folder);
    }
    for (final WorkspaceDoc doc in documents) {
      doc.updatedAt = _stamp();
      _docs.add(doc);
    }
    for (final WorkspaceDocumentUpdate update in updates) {
      final WorkspaceDoc document = byId(update.documentId)!;
      bool documentChanged = false;
      if (update.runtimeId != null) {
        final String canonical = sanitizeMenuId(update.runtimeId!);
        if (canonical != document.runtimeId) {
          document.runtimeId = canonical;
          documentChanged = true;
        }
      }
      if (update.json != null && update.json != document.json) {
        document.json = update.json!;
        documentChanged = true;
      }
      if (documentChanged) document.updatedAt = _stamp();
    }
    if (removals.isNotEmpty) {
      _docs.removeWhere((WorkspaceDoc doc) => removals.contains(doc.id));
    }
    if (_activeId == null || byId(_activeId) == null) {
      _activeId = _docs.isEmpty ? null : recent.first.id;
    }
    final bool stored = save();
    notifyListeners();
    return stored;
  }

  bool replaceDocument({
    required String id,
    required String title,
    required String json,
    required WorkspaceDocKind kind,
    String? folderId,
    String? runtimeId,
    String? presetContext,
  }) {
    if (!canWrite ||
        !_isJson(json) ||
        (folderId != null && folderById(folderId) == null)) {
      return false;
    }
    final WorkspaceDoc? doc = byId(id);
    if (doc == null) return false;
    final String? canonicalRuntimeId = kind.hasRuntimeId
        ? sanitizeMenuId(runtimeId ?? title)
        : null;
    doc
      ..title = normalizeWorkspaceTitle(
        title,
        fallback: canonicalRuntimeId ?? huiText('Untitled panel'),
      )
      ..runtimeId = canonicalRuntimeId
      ..json = json
      ..kind = kind
      ..presetContext = presetContext
      ..folderId = folderId
      ..updatedAt = _stamp();
    save();
    notifyListeners();
    return true;
  }

  bool delete(String id) {
    if (!canWrite) return false;
    final WorkspaceDoc? doc = byId(id);
    if (doc == null) return false;
    _docs.remove(doc);
    if (_activeId == id) {
      _activeId = _docs.isEmpty ? null : recent.first.id;
    }
    save();
    notifyListeners();
    return true;
  }

  /// Leaves no document open.
  bool close() {
    if (_activeId == null) return false;
    _activeId = null;
    save();
    notifyListeners();
    return true;
  }

  bool switchTo(String id) {
    if (byId(id) == null) return false;
    if (_activeId == id) return true;
    _activeId = id;
    save();
    notifyListeners();
    return true;
  }

  bool moveDocument(String id, String? folderId) {
    if (!canWrite) return false;
    final WorkspaceDoc? doc = byId(id);
    if (doc == null || (folderId != null && folderById(folderId) == null)) {
      return false;
    }
    if (doc.folderId == folderId) return true;
    doc.folderId = folderId;
    doc.updatedAt = _stamp();
    save();
    notifyListeners();
    return true;
  }

  WorkspaceFolder createFolder({required String title, String? parentId}) {
    _requireReady();
    _requireWritable();
    final String? canonicalParent = folderById(parentId)?.id;
    final WorkspaceFolder folder = WorkspaceFolder(
      id: _newUniqueId(<String>{
        for (final WorkspaceFolder folder in _folders) folder.id,
      }),
      title: normalizeWorkspaceTitle(title, fallback: 'Folder'),
      parentId: canonicalParent,
      updatedAt: _stamp(),
    );
    _folders.add(folder);
    save();
    notifyListeners();
    return folder;
  }

  bool renameFolder(String id, String title) {
    if (!canWrite) return false;
    final WorkspaceFolder? folder = folderById(id);
    if (folder == null) return false;
    final String normalized = normalizeWorkspaceTitle(
      title,
      fallback: 'Folder',
    );
    if (folder.title == normalized) return true;
    folder.title = normalized;
    folder.updatedAt = _stamp();
    save();
    notifyListeners();
    return true;
  }

  bool moveFolder(String id, String? parentId) {
    if (!canWrite) return false;
    final WorkspaceFolder? folder = folderById(id);
    if (folder == null) return false;
    final String? canonicalParent = parentId == null
        ? null
        : folderById(parentId)?.id;
    if (parentId != null && canonicalParent == null) return false;
    if (canonicalParent == id || _isDescendant(canonicalParent, id)) {
      return false;
    }
    if (folder.parentId == canonicalParent) return true;
    folder.parentId = canonicalParent;
    folder.updatedAt = _stamp();
    save();
    notifyListeners();
    return true;
  }

  bool deleteFolder(String id) {
    if (!canWrite) return false;
    final WorkspaceFolder? folder = folderById(id);
    if (folder == null) return false;
    final String? replacement = folder.parentId;
    for (final WorkspaceDoc doc in _docs) {
      if (doc.folderId == id) doc.folderId = replacement;
    }
    for (final WorkspaceFolder child in _folders) {
      if (child.parentId == id) child.parentId = replacement;
    }
    _folders.remove(folder);
    save();
    notifyListeners();
    return true;
  }

  bool updateActive({
    String? title,
    String? runtimeId,
    String? json,
    WorkspaceDocKind? kind,
    String? folderId,
  }) {
    if (!canWrite) return false;
    final WorkspaceDoc? doc = active;
    if (doc == null) return false;
    bool changed = false;
    if (title != null) {
      final String normalized = normalizeWorkspaceTitle(
        title,
        fallback: doc.runtimeId ?? huiText('Untitled panel'),
      );
      if (normalized != doc.title) {
        doc.title = normalized;
        changed = true;
      }
    }
    if (kind != null && kind != doc.kind) {
      doc.kind = kind;
      doc.runtimeId = kind.hasRuntimeId
          ? sanitizeMenuId(runtimeId ?? doc.runtimeId ?? doc.title)
          : null;
      changed = true;
    } else if (runtimeId != null && doc.kind.hasRuntimeId) {
      final String canonical = sanitizeMenuId(runtimeId);
      if (canonical != doc.runtimeId) {
        doc.runtimeId = canonical;
        changed = true;
      }
    }
    if (json != null && json != doc.json) {
      doc.json = json;
      changed = true;
    }
    if (folderId != null &&
        folderById(folderId) != null &&
        folderId != doc.folderId) {
      doc.folderId = folderId;
      changed = true;
    }
    if (!changed) return true;
    doc.updatedAt = _stamp();
    final bool stored = save();
    notifyListeners();
    return stored;
  }

  bool save() {
    if (!_isReady || _loadProtected || _requiresReload || _writesPaused) {
      return false;
    }
    final int revision = ++_saveRevision;
    final WorkspaceRepository source = repository;
    // A layered store works out what changed when the write runs, so a
    // queued save writes against what the earlier ones stored.
    final FutureOr<bool> Function() write =
        source is IncrementalWorkspaceRepository
        ? () => _writeChanges(source as IncrementalWorkspaceRepository)
        : _wholeWrite();
    final Future<void>? tail = _writeTail;
    if (tail != null) {
      final Future<void> queued = tail.then((_) async {
        try {
          final bool stored = await write();
          _finishWrite(revision, stored);
        } catch (_) {
          _finishWrite(revision, false);
        }
      });
      _trackWrite(queued);
      return true;
    }

    final FutureOr<bool> result;
    try {
      result = write();
    } catch (_) {
      _finishWrite(revision, false);
      return false;
    }
    if (result is bool) {
      _finishWrite(revision, result);
      return result;
    }
    final Future<void> pending = _finishAsyncWrite(result, revision);
    _trackWrite(pending);
    return true;
  }

  /// The revision of the workspace in storage, as this tab last wrote or
  /// read it.
  int get storedRevision => _storedRevision;

  /// The whole workspace as one record, encoded now.
  FutureOr<bool> Function() _wholeWrite() {
    final int stored = ++_issuedRevision;
    final String payload = _payload(_snapshot(), stored);
    return () => _afterWrite(repository.write(key, payload), stored);
  }

  String _payload(_WorkspaceState state, int stored) =>
      jsonEncode(<String, dynamic>{...state.toJson(), 'revision': stored});

  FutureOr<bool> _afterWrite(
    FutureOr<bool> result,
    int stored, [
    void Function()? landed,
  ]) {
    void take(bool ok) {
      if (!ok) return;
      if (stored > _storedRevision) _storedRevision = stored;
      landed?.call();
    }

    if (result is bool) {
      take(result);
      return result;
    }
    return result.then((bool ok) {
      take(ok);
      return ok;
    });
  }

  /// Writes what changed since the layered store last matched this tab: the
  /// changed documents, the layout when it moved, and the header. Without a
  /// known match, or when the store refuses the changes, the whole workspace
  /// goes instead.
  Future<bool> _writeChanges(IncrementalWorkspaceRepository source) async {
    final _StoredLayout? stored = _stored;
    if (stored != null) {
      final int next = _issuedRevision + 1;
      final (WorkspaceDelta? delta, _StoredLayout now) = _StoredLayout.changes(
        stored,
        this,
        next,
      );
      if (delta == null) return true;
      _issuedRevision = next;
      final bool ok = await _afterWrite(
        source.writeDelta(key, delta),
        next,
        () => _stored = now,
      );
      if (ok || _repositoryNeedsReload) return ok;
      _stored = null;
    }
    final int next = ++_issuedRevision;
    final _StoredLayout now = _StoredLayout.of(this);
    return _afterWrite(
      repository.write(key, _payload(_snapshot(), next)),
      next,
      () => _stored = now,
    );
  }

  bool get _repositoryNeedsReload {
    final WorkspaceRepository source = repository;
    return source is ObservableWorkspaceRepository &&
        (source as ObservableWorkspaceRepository).requiresReload;
  }

  /// Storage now holds [state] at [revision]: a layered store's next save
  /// writes only what differs from it.
  void _matchStored(
    _WorkspaceState state,
    int revision, {
    bool layered = true,
  }) {
    _storedRevision = revision;
    if (revision > _issuedRevision) _issuedRevision = revision;
    _stored = layered && repository is IncrementalWorkspaceRepository
        ? _StoredLayout.ofState(state)
        : null;
  }

  Future<bool> retrySave() async {
    if (!canWrite) return false;
    save();
    await writesSettled;
    return !hasUnsavedChanges;
  }

  int estimateBytes() {
    final String payload = jsonEncode(_snapshot().toJson());
    return (key.length + payload.length) * 2;
  }

  Map<String, dynamic> exportPortableState() => _snapshot().toJson();

  String createRollbackSnapshot() => jsonEncode(_snapshot().toJson());

  Future<bool> restoreRollbackSnapshot(String raw) async {
    final _DecodedWorkspace decoded = _decodeV2(raw);
    final Future<void>? pending = _writeTail;
    if (pending != null) await pending;
    _applyState(decoded.state, warning: decoded.warning);
    if (!save()) return false;
    await writesSettled;
    return !hasUnsavedChanges;
  }

  WorkspacePortableStateCheck inspectPortableState(Object? raw) {
    try {
      final _DecodedWorkspace decoded = _decodeV2(jsonEncode(raw));
      return WorkspacePortableStateCheck._messages(
        isValid: true,
        documentCount: decoded.state.docs.length,
        folderCount: decoded.state.folders.length,
        warning: decoded.warning,
      );
    } on _FutureWorkspaceVersion catch (error) {
      return WorkspacePortableStateCheck(
        isValid: false,
        documentCount: 0,
        folderCount: 0,
        error: error.message,
      );
    } catch (_) {
      return WorkspacePortableStateCheck(
        isValid: false,
        documentCount: 0,
        folderCount: 0,
        error: 'The workspace state is unreadable.',
      );
    }
  }

  Future<bool> replacePortableState(Object? raw) async {
    if (_requiresReload) {
      _lastError = _workspaceText(
        'This workspace changed in another browser tab. Export this tab '
        'before reloading to choose which version to keep.',
      );
      notifyListeners();
      return false;
    }
    final _DecodedWorkspace decoded;
    try {
      decoded = _decodeV2(jsonEncode(raw));
    } catch (_) {
      _lastError = _workspaceText('The workspace state is unreadable.');
      notifyListeners();
      return false;
    }
    final Future<void>? pending = _writeTail;
    if (pending != null) await pending;
    final int storedRevision = ++_issuedRevision;
    final String payload = _payload(decoded.state, storedRevision);
    final int revision = _saveRevision + 1;
    final bool stored;
    try {
      final WorkspaceRepository source = repository;
      stored = _loadProtected && source is RecoverableWorkspaceRepository
          ? await (source as RecoverableWorkspaceRepository).restore(
              key,
              payload,
            )
          : await source.write(key, payload);
    } catch (_) {
      _syncRepositoryConflict();
      _lastError = _repositoryFailure();
      _lastError ??= _workspaceText(
        'The imported workspace could not be saved.',
      );
      _quotaExceeded = !_requiresReload;
      notifyListeners();
      return false;
    }
    if (!stored) {
      _syncRepositoryConflict();
      _lastError = _repositoryFailure();
      _lastError ??= _workspaceText(
        'The imported workspace could not be saved.',
      );
      _quotaExceeded = !_requiresReload;
      notifyListeners();
      return false;
    }
    _saveRevision = revision;
    _persistedRevision = revision;
    _loadProtected = false;
    _requiresReload = false;
    _matchStored(decoded.state, storedRevision);
    _applyState(decoded.state, warning: decoded.warning);
    return true;
  }

  Future<bool> reset() async {
    final Future<void>? pending = _writeTail;
    if (pending != null) await pending;
    final WorkspaceRepository source = repository;
    if (source is! WorkspaceMaintenanceRepository) {
      _lastError = _workspaceText(
        'This workspace repository cannot clear saved data.',
      );
      notifyListeners();
      return false;
    }
    final bool cleared;
    try {
      final WorkspaceMaintenanceRepository maintenance =
          source as WorkspaceMaintenanceRepository;
      cleared = await maintenance.clear();
    } catch (_) {
      _lastError = _workspaceText(
        'Browser storage refused to clear the workspace.',
      );
      notifyListeners();
      return false;
    }
    if (!cleared) {
      final RecoverableWorkspaceRepository? recoverable =
          source is RecoverableWorkspaceRepository
          ? source as RecoverableWorkspaceRepository
          : null;
      _lastError = recoverable?.lastFailure == null
          ? _workspaceText('Browser storage refused to clear the workspace.')
          : () =>
                recoverable?.lastFailure ??
                huiText('Browser storage refused to clear the workspace.');
      notifyListeners();
      return false;
    }
    _saveRevision = 0;
    _persistedRevision = 0;
    _requiresReload = false;
    _stored = null;
    _storedRevision = 0;
    _resetToEmpty();
    _lastError = null;
    _isReady = true;
    notifyListeners();
    return true;
  }

  Future<void> _load() {
    _isReady = false;
    _lastError = null;
    _stored = null;
    _storedRevision = 0;
    final FutureOr<String?> result;
    try {
      result = repository.read(key);
    } on WorkspaceRepositoryCorruption {
      return _loadBackup(
        fallbackError:
            'The saved IndexedDB workspace record was malformed and no valid recovery copy was found.',
      );
    } catch (_) {
      return _loadBackup(
        fallbackError:
            'The saved workspace could not be read and no valid recovery copy was found.',
      );
    }
    if (result is String?) {
      return _loadV2(result);
    }
    return result.then(_loadV2).catchError((Object error) {
      if (error is WorkspaceRepositoryCorruption) {
        return _loadBackup(
          fallbackError:
              'The saved IndexedDB workspace record was malformed and no valid recovery copy was found.',
        );
      }
      return _loadBackup(
        fallbackError:
            'The saved workspace could not be read and no valid recovery copy was found.',
      );
    });
  }

  Future<void> _loadV2(String? raw) {
    if (raw != null && raw.isNotEmpty) {
      try {
        final _DecodedWorkspace decoded = _decodeV2(raw);
        _syncRepositoryConflict();
        final _WorkspaceMessage? warning = _mergeWarnings(
          decoded.warning,
          _repositoryFailure(),
        );
        // A repaired read left entries in storage that the workspace
        // dropped; the next save writes it whole.
        _matchStored(
          decoded.state,
          decoded.state.revision,
          layered: warning == null,
        );
        _applyState(decoded.state, warning: warning);
        return Future<void>.value();
      } on _FutureWorkspaceVersion catch (error) {
        _completeEmpty(_workspaceText(error.message), protectStoredData: true);
        return Future<void>.value();
      } catch (_) {
        return _loadBackup(
          fallbackError:
              'The saved v2 workspace was unreadable and no valid backup was found.',
        );
      }
    }
    return _loadLegacy();
  }

  Future<void> _loadBackup({required String fallbackError}) async {
    final WorkspaceRepository source = repository;
    if (source is! RecoverableWorkspaceRepository) {
      await _loadLegacy(fallbackError: fallbackError);
      return;
    }
    final RecoverableWorkspaceRepository recoverable =
        source as RecoverableWorkspaceRepository;
    String? backup;
    try {
      backup = await recoverable.readBackup(key);
    } catch (_) {
      backup = null;
    }
    if (await _tryRecoveryCandidate(backup, recoverable)) return;

    final String? retained;
    try {
      retained = await recoverable.readRetainedCopy(key);
    } catch (_) {
      await _loadLegacy(fallbackError: fallbackError);
      return;
    }
    if (await _tryRecoveryCandidate(retained, recoverable)) return;
    await _loadLegacy(fallbackError: fallbackError);
  }

  Future<bool> _tryRecoveryCandidate(
    String? raw,
    RecoverableWorkspaceRepository recoverable,
  ) async {
    if (raw == null || raw.isEmpty) return false;
    final _DecodedWorkspace recovered;
    try {
      recovered = _decodeV2(raw);
    } on _FutureWorkspaceVersion catch (error) {
      _completeEmpty(_workspaceText(error.message), protectStoredData: true);
      return true;
    } catch (_) {
      return false;
    }
    final bool stored;
    try {
      stored = await recoverable.restore(key, raw);
    } catch (_) {
      _applyRecoveredBackup(recovered, false);
      return true;
    }
    _applyRecoveredBackup(recovered, stored);
    return true;
  }

  void _applyRecoveredBackup(_DecodedWorkspace recovered, bool stored) {
    if (!stored) _syncRepositoryConflict();
    if (stored) {
      _matchStored(recovered.state, recovered.state.revision, layered: false);
    }
    _applyState(
      recovered.state,
      warning: stored
          ? _workspaceText(
              'The latest workspace snapshot was unreadable, so the previous '
              'transaction was restored.',
            )
          : _workspaceText(
              'The previous workspace transaction was recovered in memory, but '
              'could not be restored to browser storage. Export it before '
              'closing this tab.',
            ),
      quotaExceeded: !stored,
      loadProtected: !stored,
    );
  }

  Future<void> _loadLegacy({String? fallbackError}) {
    // Only the browser workspace has a pre-v2 record to migrate.
    if (key != storageKey) {
      _completeEmpty(
        fallbackError == null ? null : _workspaceText(fallbackError),
        protectStoredData: fallbackError != null,
      );
      return Future<void>.value();
    }
    final FutureOr<String?> result;
    try {
      result = repository.read(legacyStorageKey);
    } catch (_) {
      _completeEmpty(
        _workspaceText(
          fallbackError ?? 'The saved workspace could not be read.',
        ),
        protectStoredData: true,
      );
      return Future<void>.value();
    }
    if (result is String?) {
      return _finishLegacy(result, fallbackError: fallbackError);
    }
    return result
        .then((String? raw) => _finishLegacy(raw, fallbackError: fallbackError))
        .catchError((Object _) {
          _completeEmpty(
            _workspaceText(
              fallbackError ?? 'The saved workspace could not be read.',
            ),
            protectStoredData: true,
          );
        });
  }

  Future<void> _finishLegacy(String? raw, {String? fallbackError}) {
    if (raw == null || raw.isEmpty) {
      if (fallbackError == null) {
        _completeEmpty(null);
      } else {
        _completeEmpty(_workspaceText(fallbackError), protectStoredData: true);
      }
      return Future<void>.value();
    }

    final _DecodedWorkspace migrated;
    try {
      migrated = _decodeLegacy(raw);
    } catch (_) {
      _completeEmpty(
        _workspaceText(
          fallbackError ?? 'The saved workspace was unreadable and was reset.',
        ),
        protectStoredData: true,
      );
      return Future<void>.value();
    }

    final String payload = jsonEncode(migrated.state.toJson());
    final FutureOr<bool> result;
    try {
      final WorkspaceRepository source = repository;
      result = fallbackError != null && source is RecoverableWorkspaceRepository
          ? (source as RecoverableWorkspaceRepository).restore(
              key,
              payload,
            )
          : source.write(key, payload);
    } catch (_) {
      _applyState(
        migrated.state,
        warning: _workspaceText(
          'The legacy workspace was recovered in memory, but its v2 migration could not be saved.',
        ),
        quotaExceeded: true,
        loadProtected: true,
      );
      return Future<void>.value();
    }
    if (result is bool) {
      _finishMigration(migrated, result);
      return Future<void>.value();
    }
    return result
        .then((bool stored) => _finishMigration(migrated, stored))
        .catchError((Object _) => _finishMigration(migrated, false));
  }

  void _finishMigration(_DecodedWorkspace migrated, bool stored) {
    final _WorkspaceMessage? warning = stored
        ? migrated.warning
        : _workspaceText(
            'The legacy workspace was recovered in memory, but its v2 migration could not be saved.',
          );
    if (!stored) _syncRepositoryConflict();
    _applyState(
      migrated.state,
      warning: warning,
      quotaExceeded: !stored,
      loadProtected: !stored,
    );
  }

  _DecodedWorkspace _decodeV2(String raw) {
    final Object? decoded = jsonDecode(raw);
    if (decoded is! Map) throw const FormatException('Workspace is not a map');
    final Object? rawVersion = decoded['schemaVersion'];
    if (rawVersion is num && rawVersion.toInt() > schemaVersion) {
      throw const _FutureWorkspaceVersion(
        'This workspace was written by a newer editor and was left untouched.',
      );
    }
    if (rawVersion is! num || rawVersion.toInt() != schemaVersion) {
      throw const FormatException('Unknown workspace schema');
    }

    int repaired = 0;
    final Set<String> folderIds = <String>{};
    final List<WorkspaceFolder> folders = <WorkspaceFolder>[];
    final Object? rawFolders = decoded['folders'];
    if (rawFolders is List) {
      for (final Object? entry in rawFolders) {
        final WorkspaceFolder? folder = WorkspaceFolder.fromJson(entry);
        if (folder == null || !folderIds.add(folder.id)) {
          repaired++;
          continue;
        }
        folders.add(folder);
      }
    }

    final String? legacyUnfiledId = decoded['unfiledFolderId'] is String
        ? decoded['unfiledFolderId'] as String
        : null;
    if (legacyUnfiledId != null && folderIds.remove(legacyUnfiledId)) {
      folders.removeWhere(
        (WorkspaceFolder folder) => folder.id == legacyUnfiledId,
      );
      for (final WorkspaceFolder folder in folders) {
        if (folder.parentId == legacyUnfiledId) folder.parentId = null;
      }
    }
    repaired += _repairFolderTree(folders);

    final Set<String> documentIds = <String>{};
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[];
    final Object? rawDocuments = decoded['documents'];
    if (rawDocuments is! List) {
      throw const FormatException('Workspace documents are missing.');
    }
    for (final Object? entry in rawDocuments) {
      final WorkspaceDoc? doc = WorkspaceDoc.fromJson(entry);
      if (doc == null || !documentIds.add(doc.id)) {
        repaired++;
        continue;
      }
      if (doc.folderId == legacyUnfiledId) {
        doc.folderId = null;
      } else if (doc.folderId != null && !folderIds.contains(doc.folderId)) {
        doc.folderId = null;
        repaired++;
      }
      docs.add(doc);
    }
    if (rawDocuments.isNotEmpty && docs.isEmpty) {
      throw const FormatException(
        'Every stored workspace document was rejected.',
      );
    }

    String workspaceId = decoded['workspaceId'] is String
        ? decoded['workspaceId'] as String
        : '';
    if (!isWorkspaceUuid(workspaceId)) {
      workspaceId = _newUniqueId(<String>{...folderIds, ...documentIds});
      repaired++;
    }
    final Object? rawActive = decoded['activeDocumentId'];
    String? activeId = rawActive is String && documentIds.contains(rawActive)
        ? rawActive
        : null;
    if (activeId == null && docs.isNotEmpty) {
      activeId = _newest(docs).id;
      if (rawActive != null) repaired++;
    }
    final Object? rawRevision = decoded['revision'];
    final _WorkspaceState state = _WorkspaceState(
      workspaceId: workspaceId,
      folders: folders,
      docs: docs,
      activeId: activeId,
      revision: rawRevision is num && rawRevision >= 0
          ? rawRevision.toInt()
          : 0,
    );
    return _DecodedWorkspace(
      state,
      repaired == 0
          ? null
          : _workspacePlural(
              'workspace.repaired_entries.count',
              repaired,
              oneEnglish:
                  '{count} damaged workspace entry was repaired or skipped.',
              otherEnglish:
                  '{count} damaged workspace entries were repaired or skipped.',
            ),
    );
  }

  _DecodedWorkspace _decodeLegacy(String raw) {
    final Object? decoded = jsonDecode(raw);
    if (decoded is! Map) throw const FormatException('Workspace is not a map');

    final Set<String> used = <String>{};
    final String workspaceId = _newUniqueId(used);
    used.add(workspaceId);
    final Map<String, String> migratedIds = <String, String>{};
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[];
    int skipped = 0;
    final Object? rawDocs = decoded['docs'];
    if (rawDocs is List) {
      for (final Object? entry in rawDocs) {
        if (entry is! Map) {
          skipped++;
          continue;
        }
        final Object? legacyId = entry['id'];
        final Object? rawJson = entry['json'];
        if (legacyId is! String ||
            legacyId.isEmpty ||
            migratedIds.containsKey(legacyId) ||
            rawJson is! String ||
            rawJson.isEmpty ||
            !_isJson(rawJson)) {
          skipped++;
          continue;
        }
        final WorkspaceDocKind? kind = entry['kind'] == null
            ? WorkspaceDocKind.menu
            : WorkspaceDocKind.fromName(entry['kind']);
        if (kind == null || kind == WorkspaceDocKind.panel) {
          skipped++;
          continue;
        }
        final String id = isWorkspaceUuid(legacyId) && used.add(legacyId)
            ? legacyId
            : _newUniqueId(used);
        used.add(id);
        migratedIds[legacyId] = id;
        final String rawName = entry['name'] is String
            ? entry['name'] as String
            : huiDefaultMenuId;
        final String runtimeId = sanitizeMenuId(rawName);
        final Object? rawUpdatedAt = entry['updatedAt'];
        docs.add(
          WorkspaceDoc(
            id: id,
            title: normalizeWorkspaceTitle(rawName, fallback: runtimeId),
            runtimeId: runtimeId,
            json: rawJson,
            updatedAt: rawUpdatedAt is num ? rawUpdatedAt.toInt() : 0,
            folderId: null,
            kind: kind,
          ),
        );
      }
    }
    final Object? legacyActive = decoded['activeId'];
    String? activeId = legacyActive is String
        ? migratedIds[legacyActive]
        : null;
    if (activeId == null && docs.isNotEmpty) activeId = _newest(docs).id;

    return _DecodedWorkspace(
      _WorkspaceState(
        workspaceId: workspaceId,
        folders: const <WorkspaceFolder>[],
        docs: docs,
        activeId: activeId,
      ),
      skipped == 0
          ? null
          : _workspacePlural(
              'workspace.legacy_damaged.count',
              skipped,
              oneEnglish: '{count} legacy document was damaged and skipped.',
              otherEnglish:
                  '{count} legacy documents were damaged and skipped.',
            ),
    );
  }

  void _applyState(
    _WorkspaceState state, {
    _WorkspaceMessage? warning,
    bool quotaExceeded = false,
    bool loadProtected = false,
  }) {
    _workspaceId = state.workspaceId;
    _folders
      ..clear()
      ..addAll(state.folders);
    _docs
      ..clear()
      ..addAll(state.docs);
    _activeId = state.activeId;
    _lastStamp = 0;
    for (final WorkspaceFolder folder in _folders) {
      if (folder.updatedAt > _lastStamp) _lastStamp = folder.updatedAt;
    }
    for (final WorkspaceDoc doc in _docs) {
      if (doc.updatedAt > _lastStamp) _lastStamp = doc.updatedAt;
    }
    _lastError = warning;
    _quotaExceeded = quotaExceeded;
    _loadProtected = loadProtected;
    _isReady = true;
    notifyListeners();
  }

  void _completeEmpty(
    _WorkspaceMessage? error, {
    bool protectStoredData = false,
  }) {
    _resetToEmpty();
    _lastError = error;
    _loadProtected = protectStoredData;
    _isReady = true;
    notifyListeners();
  }

  void _resetToEmpty() {
    final Set<String> used = <String>{};
    _workspaceId = _newUniqueId(used);
    used.add(_workspaceId);
    _folders.clear();
    _docs.clear();
    _activeId = null;
    _lastStamp = 0;
    _quotaExceeded = false;
    _loadProtected = false;
    _requiresReload = false;
  }

  _WorkspaceState _snapshot() => _WorkspaceState(
    workspaceId: _workspaceId,
    folders: _folders,
    docs: _docs,
    activeId: _activeId,
  );

  int _stamp() {
    final int now = DateTime.now().millisecondsSinceEpoch;
    _lastStamp = now > _lastStamp ? now : _lastStamp + 1;
    return _lastStamp;
  }

  String _newUniqueId(Set<String> used) {
    for (int attempt = 0; attempt < 1000; attempt++) {
      final String candidate = _idFactory();
      if (isWorkspaceUuid(candidate) && !used.contains(candidate)) {
        return candidate;
      }
    }
    throw StateError(
      huiText('The workspace id factory did not produce a unique UUID.'),
    );
  }

  bool _isDescendant(String? candidateId, String ancestorId) {
    String? cursor = candidateId;
    final Set<String> seen = <String>{};
    while (cursor != null && seen.add(cursor)) {
      if (cursor == ancestorId) return true;
      cursor = folderById(cursor)?.parentId;
    }
    return false;
  }

  void _requireReady() {
    if (!_isReady) {
      throw StateError(
        huiText('The workspace repository has not finished loading.'),
      );
    }
  }

  void _requireWritable() {
    if (_loadProtected || _requiresReload) {
      throw StateError(
        _requiresReload
            ? huiText(
                'This workspace changed in another browser tab. Export this tab before reloading.',
              )
            : huiText(
                'The saved workspace is protected from overwrite. Export or import a workspace before creating documents.',
              ),
      );
    }
  }

  void _trackWrite(Future<void> pending) {
    _writeTail = pending;
    pending.whenComplete(() {
      if (identical(_writeTail, pending)) _writeTail = null;
    });
  }

  Future<void> _finishAsyncWrite(Future<bool> result, int revision) async {
    try {
      _finishWrite(revision, await result);
    } catch (_) {
      _finishWrite(revision, false);
    }
  }

  void _finishWrite(int revision, bool stored) {
    if (revision != _saveRevision) return;
    if (stored) {
      _persistedRevision = revision;
      _quotaExceeded = false;
      _lastError = null;
    } else {
      _syncRepositoryConflict();
      _quotaExceeded = !_requiresReload;
      final WorkspaceRepository source = repository;
      final RecoverableWorkspaceRepository? recoverable =
          source is RecoverableWorkspaceRepository
          ? source as RecoverableWorkspaceRepository
          : null;
      _lastError = recoverable?.lastFailure == null
          ? null
          : () =>
                recoverable?.lastFailure ??
                huiText(
                  'Browser storage is full, so this change was not saved. Export the document to keep it.',
                );
      _lastError ??= _workspaceText(
        'Browser storage is full, so this change was not saved. '
        'Export the document to keep it.',
      );
    }
    if (_writeTail != null) notifyListeners();
  }

  _WorkspaceMessage? _repositoryFailure() {
    final WorkspaceRepository source = repository;
    if (source is! RecoverableWorkspaceRepository) return null;
    final RecoverableWorkspaceRepository recoverable =
        source as RecoverableWorkspaceRepository;
    if (recoverable.lastFailure == null) return null;
    return () =>
        recoverable.lastFailure ?? huiText('Workspace repository failed.');
  }

  void _syncRepositoryConflict() {
    final WorkspaceRepository source = repository;
    if (source is ObservableWorkspaceRepository &&
        (source as ObservableWorkspaceRepository).requiresReload) {
      _requiresReload = true;
    }
  }

  void _handleRepositoryNotice(WorkspaceRepositoryNotice notice) {
    if (notice.kind == WorkspaceRepositoryNoticeKind.changed &&
        notice.key != key) {
      return;
    }
    // The tab holding this workspace writes it while this one waits.
    if (_writesPaused) return;
    _requiresReload = true;
    _quotaExceeded = false;
    _lastError = notice.kind == WorkspaceRepositoryNoticeKind.cleared
        ? _workspaceText(
            'Local workspace data was reset in another browser tab. Export this '
            'tab before reloading if it contains work you need.',
          )
        : _workspaceText(
            'This workspace changed in another browser tab. Export this tab '
            'before reloading to choose which version to keep.',
          );
    notifyListeners();
  }

  @override
  void dispose() {
    final WorkspaceRepository source = repository;
    if (source is ObservableWorkspaceRepository) {
      final ObservableWorkspaceRepository observable =
          source as ObservableWorkspaceRepository;
      observable.setNoticeListener(null);
    }
    super.dispose();
  }

  _WorkspaceMessage? _mergeWarnings(
    _WorkspaceMessage? first,
    _WorkspaceMessage? second,
  ) {
    if (first == null) return second;
    if (second == null) return first;
    return () {
      final String firstMessage = first();
      final String secondMessage = second();
      if (firstMessage.isEmpty) return secondMessage;
      if (secondMessage.isEmpty) return firstMessage;
      return '$firstMessage $secondMessage';
    };
  }

  int _repairFolderTree(List<WorkspaceFolder> folders) {
    int repaired = 0;
    final Map<String, WorkspaceFolder> byId = <String, WorkspaceFolder>{
      for (final WorkspaceFolder folder in folders) folder.id: folder,
    };
    for (final WorkspaceFolder folder in folders) {
      final String? parentId = folder.parentId;
      if (parentId != null && !byId.containsKey(parentId)) {
        folder.parentId = null;
        repaired++;
      }
    }
    for (final WorkspaceFolder folder in folders) {
      final Set<String> seen = <String>{folder.id};
      String? cursor = folder.parentId;
      while (cursor != null) {
        if (!seen.add(cursor)) {
          folder.parentId = null;
          repaired++;
          break;
        }
        cursor = byId[cursor]?.parentId;
      }
    }
    return repaired;
  }

  static WorkspaceDoc _newest(List<WorkspaceDoc> docs) {
    WorkspaceDoc newest = docs.first;
    for (int index = 1; index < docs.length; index++) {
      if (docs[index].updatedAt > newest.updatedAt) newest = docs[index];
    }
    return newest;
  }
}

class _WorkspaceState {
  const _WorkspaceState({
    required this.workspaceId,
    required this.folders,
    required this.docs,
    required this.activeId,
    this.revision = 0,
  });

  final String workspaceId;
  final List<WorkspaceFolder> folders;
  final List<WorkspaceDoc> docs;
  final String? activeId;

  /// The revision storage held it at; 0 for state that never was stored.
  final int revision;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'schemaVersion': Workspace.schemaVersion,
    'workspaceId': workspaceId,
    'folders': folders
        .map((WorkspaceFolder folder) => folder.toJson())
        .toList(),
    'documents': docs.map((WorkspaceDoc doc) => doc.toJson()).toList(),
    'activeDocumentId': activeId,
  };
}

class _DecodedWorkspace {
  const _DecodedWorkspace(this.state, this.warning);

  final _WorkspaceState state;
  final _WorkspaceMessage? warning;
}

class _FutureWorkspaceVersion implements Exception {
  const _FutureWorkspaceVersion(this.message);

  final String message;
}

final RegExp _workspaceUuidPattern = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
  caseSensitive: false,
);

bool isWorkspaceUuid(String value) => _workspaceUuidPattern.hasMatch(value);

String newWorkspaceUuid() {
  final Random random = Random.secure();
  final List<int> bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final String hex = bytes
      .map((int byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-'
      '${hex.substring(20)}';
}

String normalizeWorkspaceTitle(Object? raw, {required String fallback}) {
  if (raw is! String) return fallback;
  final StringBuffer out = StringBuffer();
  for (final int rune in raw.trim().runes) {
    if (rune >= 32 && rune != 127) out.writeCharCode(rune);
    if (out.length >= 120) break;
  }
  final String title = out.toString().trim();
  return title.isEmpty ? fallback : title;
}

bool _isJson(String raw) {
  try {
    jsonDecode(raw);
    return true;
  } catch (_) {
    return false;
  }
}

/// One document's fields as a layered store last took them.
final class _StoredDoc {
  _StoredDoc(WorkspaceDoc doc)
    : title = doc.title,
      runtimeId = doc.runtimeId,
      json = doc.json,
      kind = doc.kind,
      folderId = doc.folderId,
      updatedAt = doc.updatedAt,
      presetContext = doc.presetContext;

  final String title;
  final String? runtimeId;
  final String json;
  final WorkspaceDocKind kind;
  final String? folderId;
  final int updatedAt;
  final String? presetContext;

  bool matches(WorkspaceDoc doc) =>
      (identical(json, doc.json) || json == doc.json) &&
      updatedAt == doc.updatedAt &&
      title == doc.title &&
      runtimeId == doc.runtimeId &&
      kind == doc.kind &&
      folderId == doc.folderId &&
      presetContext == doc.presetContext;
}

typedef _StoredFolder = (String, String, String?, int);

/// The workspace as a layered store holds it.
final class _StoredLayout {
  _StoredLayout({
    required this.workspaceId,
    required this.activeId,
    required this.order,
    required this.folders,
    required this.documents,
  });

  factory _StoredLayout.ofState(_WorkspaceState state) => _StoredLayout(
    workspaceId: state.workspaceId,
    activeId: state.activeId,
    order: <String>[for (final WorkspaceDoc doc in state.docs) doc.id],
    folders: _foldersOf(state.folders),
    documents: <String, _StoredDoc>{
      for (final WorkspaceDoc doc in state.docs) doc.id: _StoredDoc(doc),
    },
  );

  factory _StoredLayout.of(Workspace workspace) =>
      _StoredLayout.ofState(workspace._snapshot());

  final String workspaceId;
  final String? activeId;
  final List<String> order;
  final List<_StoredFolder> folders;
  final Map<String, _StoredDoc> documents;

  static List<_StoredFolder> _foldersOf(List<WorkspaceFolder> folders) =>
      <_StoredFolder>[
        for (final WorkspaceFolder folder in folders)
          (folder.id, folder.title, folder.parentId, folder.updatedAt),
      ];

  /// What [workspace] writes over [before] at [revision], and the layout it
  /// leaves; a null delta when nothing changed.
  static (WorkspaceDelta?, _StoredLayout) changes(
    _StoredLayout before,
    Workspace workspace,
    int revision,
  ) {
    final Map<String, String> written = <String, String>{};
    final Map<String, _StoredDoc> documents = <String, _StoredDoc>{};
    final List<String> order = <String>[];
    for (final WorkspaceDoc doc in workspace._docs) {
      order.add(doc.id);
      final _StoredDoc? stored = before.documents[doc.id];
      if (stored != null && stored.matches(doc)) {
        documents[doc.id] = stored;
        continue;
      }
      documents[doc.id] = _StoredDoc(doc);
      written[doc.id] = jsonEncode(doc.toJson());
    }
    final Set<String> removed = <String>{
      for (final String id in before.documents.keys)
        if (!documents.containsKey(id)) id,
    };
    final List<_StoredFolder> folders = _foldersOf(workspace._folders);
    final bool moved =
        !_sameList(order, before.order) || !_sameList(folders, before.folders);
    final _StoredLayout after = _StoredLayout(
      workspaceId: workspace._workspaceId,
      activeId: workspace._activeId,
      order: moved ? order : before.order,
      folders: moved ? folders : before.folders,
      documents: documents,
    );
    if (written.isEmpty &&
        removed.isEmpty &&
        !moved &&
        after.activeId == before.activeId &&
        after.workspaceId == before.workspaceId) {
      return (null, after);
    }
    return (
      WorkspaceDelta(
        header: jsonEncode(<String, Object?>{
          'schemaVersion': layeredWorkspaceSchema,
          'workspaceId': after.workspaceId,
          'revision': revision,
          'activeDocumentId': after.activeId,
        }),
        layout: moved
            ? jsonEncode(<String, Object?>{
                'folders': <Map<String, dynamic>>[
                  for (final WorkspaceFolder folder in workspace._folders)
                    folder.toJson(),
                ],
                'documents': order,
              })
            : null,
        documents: written,
        removed: removed,
      ),
      after,
    );
  }

  static bool _sameList<T>(List<T> a, List<T> b) {
    if (a.length != b.length) return false;
    for (int index = 0; index < a.length; index++) {
      if (a[index] != b[index]) return false;
    }
    return true;
  }
}
