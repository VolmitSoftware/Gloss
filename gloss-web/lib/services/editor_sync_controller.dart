/// The editor's half of a paired server's workspace: the catalog, filling
/// documents, server events, publishing and conflicts, as one state machine
/// the shell reads.
///
/// Once the connection is online the catalog is read in pages and every
/// document is listed at once: documents the workspace does not hold yet
/// are listed by name with a blank body and filled in the background (the
/// one opened first). Catalog events keep it current: untouched documents
/// take the server copy, edited ones read "Changed on server". Publish sends
/// only what changed here, each entry with the hash it started from; what
/// the server could not apply stays here as a conflict next to the server
/// copy until the owner picks one.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:jaspr/jaspr.dart' show ChangeNotifier;

import '../doctype/doctype.dart';
import '../l10n/hui_localizations.dart';
import '../logic/advanced_notice.dart' show ArrivedDocument;
import '../logic/sync_history.dart';
import '../logic/sync_problems.dart';
import '../state/editor_store.dart';
import '../state/workspace.dart';
import '../ui/save_state.dart';
import 'editor_api.dart';
import 'editor_sync.dart';
import 'editor_sync_workspace.dart';
import 'image_library.dart';
import 'server/error_copy.dart';
import 'server/server_connection.dart';
import 'server/server_hub.dart';
import 'server/server_profiles.dart';
import 'server/transport.dart';

part 'editor_sync_loading.dart';
part 'editor_sync_publishing.dart';

/// A document's place relative to the server, shown as a library badge.
enum SyncDocState {
  /// Edited here since the server copy.
  changedHere,

  /// The server copy moved while this tab edited it too.
  changedOnServer,

  /// Exists here only; Publish creates it.
  added,

  /// Deleted here; Publish deletes it on the server.
  deleted,

  /// The last publish could not apply it; pick a copy.
  conflict;

  String get label => switch (this) {
    SyncDocState.changedHere => huiText('Changed here'),
    SyncDocState.changedOnServer => huiText('Changed on server'),
    SyncDocState.added => huiText('New'),
    SyncDocState.deleted => huiText('Deleted'),
    SyncDocState.conflict => huiText('Conflict'),
  };

  /// Whether the owner must choose a copy before it can publish.
  bool get needsChoice =>
      this == SyncDocState.changedOnServer || this == SyncDocState.conflict;
}

/// One row of "Review changes".
final class SyncChange {
  const SyncChange({
    required this.key,
    required this.kind,
    required this.id,
    required this.name,
    required this.state,
    this.docId,
    this.image = false,
  });

  /// [editorSyncKey], or [editorSyncImageKey] for an image.
  final String key;
  final String kind;
  final String id;
  final String name;
  final SyncDocState state;

  /// The workspace document, when there is one to open.
  final String? docId;
  final bool image;
}

/// Review changes in two groups: what Publish writes or asks a choice for,
/// then what it deletes, so a deletion never hides among edits.
({List<SyncChange> writes, List<SyncChange> deletes}) reviewGroups(
  List<SyncChange> changes,
) => (
  writes: <SyncChange>[
    for (final SyncChange change in changes)
      if (change.state != SyncDocState.deleted) change,
  ],
  deletes: <SyncChange>[
    for (final SyncChange change in changes)
      if (change.state == SyncDocState.deleted) change,
  ],
);

/// The server's image library by the catalog: count and raw bytes.
typedef SyncImageWeight = ({int count, int bytes});

typedef SyncConfirm = Future<bool> Function({
  required String title,
  required String confirmLabel,
  String? message,
  bool destructive,
});

typedef SyncToast = void Function(String message, {bool error});

/// New documents the server wrote into the workspace: its listing after
/// pairing, or a document made on the server.
typedef SyncArrivals = void Function(List<ArrivedDocument> documents);

Future<bool> _refuse({
  required String title,
  required String confirmLabel,
  String? message,
  bool destructive = false,
}) async => false;

void _silent(String message, {bool error = false}) {}

void _unheard(List<ArrivedDocument> documents) {}

/// Tells listeners that the background fill's count moved.
final class SyncProgressNotifier extends ChangeNotifier {
  void notify() => notifyListeners();
}

/// "Pairing ends in n days unless used." shows this close to the end.
const Duration expiryWarning = Duration(days: 3);


class EditorSyncController extends ChangeNotifier {
  EditorSyncController({
    required this.store,
    required this.images,
    required this.hub,
    required this.serverId,
    this.bindings = const EditorSyncBindingStore(),
    this.confirm = _refuse,
    this.toast = _silent,
    this.onArrived = _unheard,
    this.origin,
    this.landEvery = const Duration(milliseconds: 250),
    DateTime Function()? clock,
    math.Random? random,
  }) : _clock = clock ?? DateTime.now,
       _random = random ?? math.Random.secure() {
    _binding = bindings.load(serverId, store.workspace);
    // An image the binding holds but the library lost (cleared storage) is
    // read again, never published as a deletion.
    for (final SyncBaseImage base in List<SyncBaseImage>.of(
      _binding.images.values,
    )) {
      if (base.local && !images.contains(base.path)) {
        _binding.images[base.path] = base.copyWith(local: false);
      }
    }
    _lastActive = _workspace.activeId;
    store.workspace.addListener(_onLocalChange);
    images.addListener(_onLocalChange);
  }

  final EditorStore store;
  final ImageLibrary images;
  final ServerHub hub;

  /// The paired server ([ServerProfile.id]) this workspace belongs to.
  final String serverId;
  final EditorSyncBindingStore bindings;
  final SyncConfirm confirm;
  final SyncToast toast;
  final SyncArrivals onArrived;

  /// This page's origin, for the `origin-not-allowed` sentence.
  final String? origin;

  /// How often filled bodies are written into the workspace while the
  /// background fill runs.
  final Duration landEvery;
  final DateTime Function() _clock;
  final math.Random _random;

  Workspace get _workspace => store.workspace;

  final SyncHashMemo _memo = SyncHashMemo();
  late EditorSyncBinding _binding;
  SyncMeta? _meta;
  SyncNotes _notes = SyncNotes.empty;
  final Map<String, SyncDocumentRow> _serverDocs = <String, SyncDocumentRow>{};
  final Map<String, SyncImageRow> _serverImages = <String, SyncImageRow>{};
  bool _catalogLoaded = false;
  bool _everOnline = false;
  bool _started = false;
  bool _owned = true;
  bool _disposed = false;
  bool _publishing = false;
  bool _readingCatalog = false;

  /// A resync was asked for while one ran; it runs again when that ends.
  bool _resyncAgain = false;

  /// A catalog read began with the documents already listed (a reconnect):
  /// it runs behind them and does not say Loading.
  bool _listedOnce = false;
  bool _fetchingImages = false;
  bool _fetchImagesAgain = false;
  bool _filling = false;
  bool _fillAgain = false;

  /// Listed documents the server refused to send, skipped by the fill until
  /// the next catalog read.
  final Set<String> _unreadable = <String>{};
  /// The last publish's outcome: `applied`, `conflict` or `rejected`.
  String? _outcome;
  String? _outcomeMessage;
  String Function()? _problem;

  /// The question a publish waits on, while it is open.
  String Function()? _question;
  ({int loaded, int total})? _progress;
  Future<void> _serial = Future<void>.value();
  int _session = 0;
  String? _lastActive;
  final List<StreamSubscription<Object?>> _listening =
      <StreamSubscription<Object?>>[];

  bool _sheetOpen = false;

  /// The Server sheet opens at Review changes (the bar's Review).
  bool _reviewFirst = false;

  // --- read side ------------------------------------------------------------

  EditorSyncBinding get binding => _binding;

  ServerProfile? get profile => hub.server(serverId);

  /// The server's name, as it reports it.
  String get serverName => profile?.name ?? serverId;

  ServerConnection get _connection => hub.connection(serverId);

  ConnectionStatus get connectionStatus => hub.status(serverId);

  ServerToken? get token => hub.token(serverId);

  bool get publishing => _publishing;

  /// Whether this tab runs live editing for the server.
  bool get owned => _owned;

  bool get serverSheetOpen => _sheetOpen;

  bool get reviewFirst => _reviewFirst;

  SyncMeta? get meta => _meta;

  /// How the background fill stands, while it runs.
  ({int loaded, int total})? get progress => _progress;

  /// Says the fill's count moved; the save bar listens, the rest of the
  /// editor does not (the controller itself notifies when the fill starts
  /// and ends).
  final SyncProgressNotifier progressChanges = SyncProgressNotifier();

  SyncProblems get problems => SyncProblems.of(_notes.warnings);

  SyncHistory get history => SyncHistory.of(_notes.history);

  /// The server's document count, by the catalog.
  int get documentCount =>
      _catalogLoaded ? _serverDocs.length : (profile?.documents ?? 0);

  int get imageCount =>
      _catalogLoaded ? _serverImages.length : (profile?.images ?? 0);

  /// Documents of kinds this build cannot edit, by kind. They stay on the
  /// server untouched.
  Map<String, int> get readOnlyKinds {
    final Map<String, int> counts = <String, int>{};
    for (final SyncDocumentRow row in _serverDocs.values) {
      if (DocumentTypeRegistry.byWireKind(row.kind) != null) continue;
      counts[row.kind] = (counts[row.kind] ?? 0) + 1;
    }
    return counts;
  }

  /// The image library's real weight from the catalog, for the Images dialog.
  SyncImageWeight? get serverImageWeight {
    if (_serverImages.isEmpty) return null;
    int bytes = 0;
    for (final SyncImageRow row in _serverImages.values) {
      bytes += row.size;
    }
    return (count: _serverImages.length, bytes: bytes);
  }

  /// When the pairing ends unless used, while that is [expiryWarning] or
  /// closer; else null.
  int? get daysLeft {
    final ServerToken? held = token;
    if (held == null) return null;
    final Duration left = held.expiresAt.difference(_clock().toUtc());
    if (left > expiryWarning) return null;
    return math.max(0, (left.inHours / 24).ceil());
  }

  /// "Pairing ends in n days unless used." within [expiryWarning] of the
  /// end; else null.
  String? get expiryLine {
    final int? days = daysLeft;
    if (days == null) return null;
    return huiPlural(
      'sync.pairing_ends',
      days,
      oneEnglish: 'Pairing ends in {count} day unless used.',
      otherEnglish: 'Pairing ends in {count} days unless used.',
    );
  }

  EditorSyncStatus get status {
    if (!_owned) return EditorSyncStatus.elsewhere;
    final ConnectionStatus connection = connectionStatus;
    switch (connection.phase) {
      case ConnectionPhase.needsPairing:
        return EditorSyncStatus.ended;
      case ConnectionPhase.refused:
        return EditorSyncStatus.refused;
      case ConnectionPhase.offline:
        return _started && _everOnline
            ? EditorSyncStatus.offline
            : EditorSyncStatus.connecting;
      case ConnectionPhase.connecting:
        // The round right after a loss tries the other path at once; the
        // bar keeps its word meanwhile, so a path switch goes unnoticed.
        if (_failingOver) break;
        return _everOnline
            ? EditorSyncStatus.reconnecting
            : EditorSyncStatus.connecting;
      case ConnectionPhase.reconnecting:
        if (_failingOver) break;
        return EditorSyncStatus.reconnecting;
      case ConnectionPhase.online:
        break;
    }
    if (_question != null) return EditorSyncStatus.asking;
    if (_publishing) return EditorSyncStatus.publishing;
    // Reading the list again after a reconnect happens behind the documents
    // already listed; only the first read and the fill say Loading.
    if (!_catalogLoaded ||
        (_readingCatalog && !_listedOnce) ||
        _progress != null) {
      return EditorSyncStatus.loading;
    }
    if (_outcome == 'rejected') return EditorSyncStatus.rejected;
    if (choiceCount > 0 || _outcome == 'conflict') {
      return EditorSyncStatus.conflict;
    }
    if (_outcome == 'applied') return EditorSyncStatus.applied;
    return EditorSyncStatus.connected;
  }

  /// One plain sentence for the bar's detail and the sheet's status line.
  String get statusDetail {
    final EditorSyncStatus current = status;
    switch (current) {
      case EditorSyncStatus.connecting:
        return huiText('Connecting to {server}', <String, Object?>{
          'server': serverName,
        });
      case EditorSyncStatus.loading:
        final ({int loaded, int total})? progress = _progress;
        if (progress == null) return huiText('Reading the server\'s list');
        return huiPlural(
          'sync.loading_documents',
          progress.total,
          oneEnglish: 'Loading {loaded} of {count} document',
          otherEnglish: 'Loading {loaded} of {count} documents',
          arguments: <String, Object?>{'loaded': progress.loaded},
        );
      case EditorSyncStatus.reconnecting:
        return connectionStatus.problem?.text ??
            huiText('Changes are kept. Reconnecting.');
      case EditorSyncStatus.offline:
        return huiText('Changes are kept. Reconnect to publish them.');
      case EditorSyncStatus.ended:
        final ErrorCopy? missing = connectionStatus.problem;
        if (missing != null && missing.code == 'no-token') return missing.text;
        return huiText(
          'Paste a new link from {command} into New link.',
          <String, Object?>{'command': pairCommand},
        );
      case EditorSyncStatus.refused:
        return connectionStatus.problem?.text ??
            transportCopy('wrong-server').text;
      case EditorSyncStatus.elsewhere:
        return huiText('Take over to edit here.');
      case EditorSyncStatus.asking:
        return _question?.call() ?? '';
      case EditorSyncStatus.publishing:
        return huiPlural(
          'sync.publishing_changes',
          pendingChangeCount,
          oneEnglish: 'Sending {count} change',
          otherEnglish: 'Sending {count} changes',
        );
      case EditorSyncStatus.rejected:
      case EditorSyncStatus.applied:
        final String? said = _outcomeMessage;
        if (said != null && said.trim().isNotEmpty) return said.trim();
        return current == EditorSyncStatus.applied
            ? huiText('Published to the server.')
            : huiText('The server refused the publish.');
      case EditorSyncStatus.conflict:
        final String? refused = _outcomeMessage;
        if (choiceCount == 0 && refused != null && refused.trim().isNotEmpty) {
          return refused.trim();
        }
        // The status already says "Needs a choice"; the detail counts.
        return huiPlural(
          'start.documents',
          choiceCount,
          oneEnglish: '{count} document',
          otherEnglish: '{count} documents',
        );
      case EditorSyncStatus.connected:
        final String? problem = _problem?.call();
        if (problem != null) return problem;
        final int changes = pendingChangeCount;
        final String line = changes == 0
            ? huiText('No changes to publish')
            : huiPlural(
                'sync.changes_to_publish',
                changes,
                oneEnglish: '{count} change to publish',
                otherEnglish: '{count} changes to publish',
              );
        final String? expiry = expiryLine;
        return expiry == null ? line : '$line. $expiry';
    }
  }

  /// The save bar's state.
  SaveBarState get saveBarState => syncSaveState(
    status: status,
    detail: statusDetail,
    publishing: _publishing && _question == null,
    changes: pendingChangeCount,
    review: choiceCount > 0 || pendingChangeCount > 0,
  );

  /// How many changes ask for a choice: edited on both sides, or refused by
  /// the last publish.
  int get choiceCount {
    _recount();
    return _choiceCount;
  }

  /// How many documents and images Publish would send now.
  int get pendingChangeCount {
    _recount();
    return _changeCount;
  }

  int _changeStamp = -1;
  int _changeCount = 0;
  int _choiceCount = 0;
  int _bindingVersion = 0;
  Map<String, String>? _baseByDocId;

  void _recount() {
    final int stamp = Object.hash(
      _workspace.saveRevision,
      _workspace.docs.length,
      images.images.length,
      _bindingVersion,
    );
    if (stamp == _changeStamp) return;
    int count = 0;
    int choices = 0;
    bool complete = true;
    for (final SyncChange change in _changes(compute: false)) {
      if (change.key.isEmpty) {
        complete = false;
      } else if (change.state.needsChoice) {
        choices++;
      } else {
        count++;
      }
    }
    if (complete) _changeStamp = stamp;
    if (count == _changeCount && choices == _choiceCount) return;
    _changeCount = count;
    _choiceCount = choices;
    _countMovedLater();
  }

  bool _countNoticeScheduled = false;

  /// A count that moved under one reader (the open sheet's Review hashes
  /// what the bar skipped) reaches every reader: the bar and the sheet say
  /// the same number.
  void _countMovedLater() {
    if (_countNoticeScheduled) return;
    _countNoticeScheduled = true;
    unawaited(
      Future<void>(() {
        _countNoticeScheduled = false;
        _notify();
      }),
    );
  }

  void _touchBinding() {
    _bindingVersion++;
    _baseByDocId = null;
  }

  Map<String, String> get _baseKeyByDocId {
    final Map<String, String>? cached = _baseByDocId;
    if (cached != null) return cached;
    final Map<String, String> built = <String, String>{
      for (final SyncBaseDocument row in _binding.documents.values)
        if (row.docId != null) row.docId!: row.key,
    };
    _baseByDocId = built;
    return built;
  }

  bool _serverMoved(String key, SyncBaseDocument? base) {
    if (!_catalogLoaded) return false;
    final SyncDocumentRow? row = _serverDocs[key];
    if (base == null) return row != null;
    return row == null || row.hash != base.hash;
  }

  /// Whether this pairing may publish [key] (its kind's scope and the
  /// token's folders).
  bool _creatable(String key) {
    final ServerToken? held = token;
    if (held == null) return false;
    final String kind = editorSyncKeyKind(key);
    final String? scope = _meta?.scopeOf(kind);
    if (scope != null && !held.has(scope)) return false;
    final List<String>? folders = held.folders;
    if (folders == null) return true;
    final String id = editorSyncKeyId(key);
    final int slash = id.lastIndexOf('/');
    if (slash < 0) return false;
    final String folder = id.substring(0, slash);
    return folders.any(
      (String allowed) => folder == allowed || folder.startsWith('$allowed/'),
    );
  }

  SyncDocState? _stateFor(WorkspaceDoc doc, SyncDocIdentity identity) {
    final String? key = identity.key;
    if (key == null) return null;
    final String? boundKey = _baseKeyByDocId[doc.id];
    final SyncBaseDocument? base = boundKey == null
        ? null
        : _binding.documents[boundKey];
    if (base != null && boundKey == key) {
      if (!base.loaded) return null;
      final bool edited = identity.hash != base.hash;
      final bool moved = _serverMoved(key, base);
      // A key the last publish could not apply needs a choice before the
      // event that moves its row arrives.
      final bool refused = _binding.conflicts.containsKey(key);
      if (edited && (moved || refused)) {
        return refused ? SyncDocState.conflict : SyncDocState.changedOnServer;
      }
      if (edited) return SyncDocState.changedHere;
      return null;
    }
    if (!_creatable(key)) return null;
    if (_serverDocs.containsKey(key) || _binding.conflicts.containsKey(key)) {
      return _binding.conflicts.containsKey(key)
          ? SyncDocState.conflict
          : SyncDocState.changedOnServer;
    }
    return SyncDocState.added;
  }

  /// The server state of one workspace document, for its library badge.
  /// Null when it matches the server.
  SyncDocState? stateOf(String documentId) {
    if (!_catalogLoaded) return null;
    final WorkspaceDoc? doc = _workspace.byId(documentId);
    if (doc == null) return null;
    final SyncDocIdentity? identity = _memo.peek(doc);
    if (identity == null) {
      _warmLater();
      return null;
    }
    return _stateFor(doc, identity);
  }

  /// Whether [documentId] is listed from the catalog and its body is still
  /// on its way.
  bool isLoading(String documentId) {
    final String? key = _baseKeyByDocId[documentId];
    final SyncBaseDocument? base = key == null ? null : _binding.documents[key];
    return base != null && !base.loaded;
  }

  /// Nothing of this server was ever listed in this browser and its first
  /// list is on its way.
  bool get firstListing => !_catalogLoaded && _binding.documents.isEmpty;

  /// What the centre says is loading: the server while its first list is on
  /// its way, else the open document's name while its body is; else null.
  String? get activeLoading {
    if (firstListing) return serverName;
    final String? active = _workspace.activeId;
    if (active == null || !isLoading(active)) return null;
    return _workspace.byId(active)?.title;
  }

  bool _warmScheduled = false;

  void _warmLater() {
    if (_warmScheduled) return;
    _warmScheduled = true;
    unawaited(Future<void>(_warm));
  }

  /// Hashes the documents and images the memo lacks, a few at a time
  /// between frames. Iterates copies: images keep arriving while it runs.
  Future<void> _warm() async {
    int done = 0;
    try {
      final List<WorkspaceDoc> docs = List<WorkspaceDoc>.of(_workspace.docs);
      for (final WorkspaceDoc doc in docs) {
        if (_disposed) return;
        if (_memo.peek(doc) != null) continue;
        _memo.of(doc);
        if (++done % 80 == 0) await Future<void>.delayed(Duration.zero);
      }
      final List<StoredImage> stored = List<StoredImage>.of(images.images);
      for (final StoredImage image in stored) {
        if (_disposed) return;
        if (_memo.peekImageHash(image) != null) continue;
        _memo.imageHash(image);
        if (++done % 20 == 0) await Future<void>.delayed(Duration.zero);
      }
    } finally {
      _warmScheduled = false;
      // A count taken while documents were unhashed left them out: count
      // again (a moved count is announced) and redraw the badges hashed here.
      _changeStamp = -1;
      _recount();
      if (done > 0) _notify();
    }
  }

  /// Everything that differs from the server, documents first.
  List<SyncChange> get changes => _changes(compute: true);

  static const SyncChange _unknown = SyncChange(
    key: '',
    kind: '',
    id: '',
    name: '',
    state: SyncDocState.changedHere,
  );

  /// With [compute] false, documents and images not hashed yet are skipped
  /// (one [_unknown] row stands for them) and hashed in the background.
  List<SyncChange> _changes({required bool compute}) {
    // Before the server's list arrived nothing can be compared with it: a
    // fresh workspace's sample document is not a change to publish.
    if (!_catalogLoaded) return const <SyncChange>[];
    final EditorSyncBinding binding = _binding;
    final List<SyncChange> result = <SyncChange>[];
    final Set<String> present = <String>{};
    bool unknown = false;
    for (final WorkspaceDoc doc in _workspace.docs) {
      final String? boundKey = _baseKeyByDocId[doc.id];
      final SyncBaseDocument? bound = boundKey == null
          ? null
          : binding.documents[boundKey];
      if (bound != null && !bound.loaded) {
        present.add(bound.key);
        continue;
      }
      final SyncDocIdentity? known = compute ? _memo.of(doc) : _memo.peek(doc);
      if (known == null) {
        unknown = true;
        if (boundKey != null) present.add(boundKey);
        continue;
      }
      final String? key = known.key;
      if (key == null) continue;
      if (boundKey == key) present.add(key);
      final SyncDocState? state = _stateFor(doc, known);
      if (state == null) continue;
      result.add(
        SyncChange(
          key: key,
          kind: editorSyncKeyKind(key),
          id: editorSyncKeyId(key),
          name: doc.title,
          state: state,
          docId: doc.id,
        ),
      );
    }
    for (final SyncBaseDocument base in binding.documents.values) {
      if (!base.editable || present.contains(base.key)) continue;
      result.add(
        SyncChange(
          key: base.key,
          kind: base.kind,
          id: base.id,
          name: base.name,
          state: binding.conflicts.containsKey(base.key)
              ? SyncDocState.conflict
              : _serverMoved(base.key, base)
              ? SyncDocState.changedOnServer
              : SyncDocState.deleted,
        ),
      );
    }
    for (final SyncChange image in _imageChanges(binding, compute: compute)) {
      if (image.key.isEmpty) {
        unknown = true;
      } else {
        result.add(image);
      }
    }
    if (unknown) {
      result.add(_unknown);
      _warmLater();
    }
    return result;
  }

  Iterable<SyncChange> _imageChanges(
    EditorSyncBinding binding, {
    required bool compute,
  }) sync* {
    final bool mayUpload = token?.has('publish:images') ?? false;
    for (final StoredImage image in List<StoredImage>.of(images.images)) {
      final SyncBaseImage? base = binding.images[image.path];
      if (base == null && !mayUpload) continue;
      final String? hash = compute
          ? _memo.imageHash(image)
          : _memo.peekImageHash(image);
      if (hash == null) {
        yield _unknown;
        continue;
      }
      final String key = editorSyncImageKey(image.path);
      SyncDocState? state;
      if (base == null) {
        state = _serverImages.containsKey(image.path)
            ? SyncDocState.changedOnServer
            : SyncDocState.added;
      } else if (!base.local) {
        continue;
      } else if (hash != base.hash) {
        final SyncImageRow? row = _serverImages[image.path];
        state = _catalogLoaded && (row == null || row.hash != base.hash)
            ? (binding.conflicts.containsKey(key)
                  ? SyncDocState.conflict
                  : SyncDocState.changedOnServer)
            : SyncDocState.changedHere;
      }
      if (state == null) continue;
      yield SyncChange(
        key: key,
        kind: 'image',
        id: image.path,
        name: image.path,
        state: state,
        image: true,
      );
    }
    for (final SyncBaseImage base in binding.images.values) {
      if (!base.local || images.contains(base.path)) continue;
      yield SyncChange(
        key: editorSyncImageKey(base.path),
        kind: 'image',
        id: base.path,
        name: base.path,
        state: SyncDocState.deleted,
        image: true,
      );
    }
  }

  // --- sheet ------------------------------------------------------------

  /// The top bar's Server button.
  void openServer({bool review = false}) {
    _sheetOpen = true;
    _reviewFirst = review;
    _notify();
  }

  void closeServer() {
    _sheetOpen = false;
    _reviewFirst = false;
    _notify();
  }

  // --- lifecycle ------------------------------------------------------------

  /// Starts live editing in this tab: connects and reads the server.
  void start() {
    if (_started || _disposed) return;
    _started = true;
    final ServerConnection connection = _connection;
    _listening.add(connection.statuses.listen(_onStatus));
    _listening.add(connection.events.listen(_onEvent));
    _listening.add(
      connection.tokenChanges.listen((ServerToken _) => _notify()),
    );
    if (connection.status.online) {
      _onStatus(connection.status);
    } else {
      unawaited(hub.connect(serverId));
    }
    _notify();
  }

  /// Another tab holds this server's workspace ([owned] false), or this tab
  /// took it back.
  set owned(bool value) {
    if (value == _owned) return;
    _owned = value;
    if (!value) {
      unawaited(hub.disconnect(serverId));
    } else if (_started) {
      unawaited(hub.connect(serverId));
    }
    _notify();
  }

  /// Connects again after Disconnect, or skips the wait while reconnecting.
  void reconnect() {
    final ConnectionStatus current = connectionStatus;
    if (current.phase == ConnectionPhase.reconnecting) {
      _connection.retryNow();
    } else {
      unawaited(hub.connect(serverId));
    }
  }

  /// Stops talking to the server; the documents and changes stay here.
  Future<void> disconnect() async {
    await hub.disconnect(serverId);
    _notify();
  }

  /// Reads the server's list again (Sync).
  Future<void> sync() async {
    if (!connectionStatus.online) {
      reconnect();
      return;
    }
    await _resync(_session);
  }

  void _onStatus(ConnectionStatus next) {
    if (_disposed) return;
    // A loss while online, then the first round of attempts (the other path
    // first): attempt 0, no retry scheduled yet.
    final bool firstRound =
        next.attempt == 0 &&
        next.retryAt == null &&
        (next.phase == ConnectionPhase.reconnecting ||
            next.phase == ConnectionPhase.connecting);
    if (firstRound && _everOnline && _sessionPhase == ConnectionPhase.online) {
      _failover?.cancel();
      _failover = Timer(ServerHub.settle, () {
        _failover = null;
        _notify();
      });
    } else if (!firstRound) {
      _failover?.cancel();
      _failover = null;
    }
    if (next.online) {
      // A move to the other path reads the catalog again: frames in transit
      // on the old path may not have arrived.
      final bool fresh =
          !_everOnline ||
          _sessionPhase != ConnectionPhase.online ||
          next.path != _sessionPath;
      _everOnline = true;
      _sessionPhase = next.phase;
      _sessionPath = next.path;
      if (fresh) {
        _session++;
        unawaited(_resync(_session));
      }
    } else {
      _sessionPhase = next.phase;
    }
    _notify();
  }

  ConnectionPhase? _sessionPhase;
  ConnectionPath? _sessionPath;

  /// Runs from a loss while online until the next path answers or
  /// [ServerHub.settle] passes: the bar keeps its word while the other path
  /// is tried, as the start page does.
  Timer? _failover;

  bool get _failingOver => _failover != null;

  EditorApi get _api => EditorApi(_connection, origin: origin);

  /// A save or an image change may change what Publish would send, and
  /// opening a document listed from the catalog reads its body now.
  void _onLocalChange() {
    if (_disposed) return;
    final String? active = _workspace.activeId;
    if (active != _lastActive) {
      _lastActive = active;
      if (active != null && isLoading(active)) {
        final String? key = _baseKeyByDocId[active];
        if (key != null) unawaited(_readNow(<String>[key]));
        _notify();
      }
    }
    if (_recountScheduled) return;
    _recountScheduled = true;
    unawaited(
      Future<void>(() {
        _recountScheduled = false;
        if (_disposed) return;
        final int before = _changeCount;
        final int choicesBefore = _choiceCount;
        _changeStamp = -1;
        if (pendingChangeCount != before || _choiceCount != choicesBefore) {
          _notify();
        }
      }),
    );
  }

  bool _recountScheduled = false;

  Future<void> _locked(Future<void> Function() body) {
    final Future<void> previous = _serial;
    final Completer<void> done = Completer<void>();
    _serial = done.future;
    return previous.then((_) => body()).whenComplete(done.complete);
  }

  /// Stores the binding. The header (a pending publish, the conflicts) goes
  /// at once, before any request it names is sent; the rows wait until the
  /// workspace write they describe has landed, so storage never holds rows
  /// ahead of the documents.
  void _persist() {
    bool kept = bindings.saveHeader(_binding);
    _rowsWanted = _workspace.saveRevision;
    if (_rowsLanded) {
      kept = _saveRows() && kept;
    } else if (!_rowsWaiting) {
      _rowsWaiting = true;
      unawaited(_saveRowsLater());
    }
    if (!kept) _warnPersist();
  }

  /// The workspace revision the binding's rows describe.
  int _rowsWanted = 0;
  bool _rowsWaiting = false;

  bool get _rowsLanded =>
      _workspace.persistedRevision >= _rowsWanted &&
      !_workspace.hasPendingWrites;

  bool _saveRows() {
    _binding.workspaceRevision = _workspace.storedRevision;
    return bindings.save(_binding);
  }

  /// Waits for the workspace writes; a write that failed leaves the rows
  /// for the next [_persist] after one lands.
  Future<void> _saveRowsLater() async {
    try {
      while (!_disposed) {
        await _workspace.writesSettled;
        if (_disposed) return;
        if (_rowsLanded) {
          if (!_saveRows()) _warnPersist();
          return;
        }
        if (!_workspace.hasPendingWrites) return;
      }
    } finally {
      _rowsWaiting = false;
    }
  }

  void _warnPersist() {
    if (_persistWarned) return;
    _persistWarned = true;
    toast(
      huiText(
        'This browser refused to store the server state. Publish before you reload.',
      ),
      error: true,
    );
  }

  bool _persistWarned = false;

  void _failed(EditorSyncFailure error) {
    if (error.ended) return;
    _problem = () => error.message;
    _notify();
  }

  void _notify() {
    if (_disposed) return;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _failover?.cancel();
    store.workspace.removeListener(_onLocalChange);
    images.removeListener(_onLocalChange);
    for (final StreamSubscription<Object?> subscription in _listening) {
      unawaited(subscription.cancel());
    }
    _listening.clear();
    super.dispose();
  }
}
