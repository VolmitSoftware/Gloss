part of 'editor_sync_controller.dart';

/// A changeset larger than this many document entries goes in parts.
const int _publishDocuments = huiEditorSyncMaxPublishDocuments;

/// A changeset body on the direct path stays under the request limit.
const int _directPublishBytes = 3 * 1024 * 1024;

/// The body of a changeset with no entries, and each entry's comma, in
/// UTF-8 bytes, rounded up.
const int _publishEnvelopeBytes = 512;

/// Sending changes and settling what moved on both sides: the changeset,
/// Publish, a publish whose answer was lost, the conflict choices and
/// history.
extension EditorSyncPublishing on EditorSyncController {
  /// What Publish sends now. Documents still listed by name only, kinds this
  /// build cannot edit and keys this pairing may not publish are never
  /// named.
  SyncChangeset buildChangeset() {
    final EditorSyncBinding binding = _binding;
    final List<SyncDocumentChange> documents = <SyncDocumentChange>[];
    final Set<String> claimed = <String>{};
    for (final WorkspaceDoc doc in _workspace.docs) {
      final String? boundKey = _baseKeyByDocId[doc.id];
      final SyncBaseDocument? bound = boundKey == null
          ? null
          : binding.documents[boundKey];
      if (bound != null && !bound.loaded) {
        claimed.add(bound.key);
        continue;
      }
      final SyncDocIdentity identity = _memo.of(doc);
      final String? key = identity.key;
      final String? wire = key == null ? null : editorSyncWireJsonOf(doc);
      if (key == null || wire == null || identity.hash == null) continue;
      final String kind = editorSyncKeyKind(key);
      final String id = editorSyncKeyId(key);
      final SyncBaseDocument? base = boundKey == key ? bound : null;
      if (base != null) {
        claimed.add(key);
        if (identity.hash == base.hash ||
            _serverMoved(key, base) ||
            binding.conflicts.containsKey(key)) {
          continue;
        }
        if (!_creatable(key)) continue;
        final String json = _versioned(kind) && base.revision != null
            ? editorSyncWithRevision(kind, wire, base.revision!)
            : wire;
        documents.add(
          SyncDocumentChange.put(
            kind: kind,
            id: id,
            baseHash: base.hash,
            json: json,
          ),
        );
        continue;
      }
      if (!_creatable(key) ||
          _serverDocs.containsKey(key) ||
          binding.documents.containsKey(key) ||
          !claimed.add(key)) {
        continue;
      }
      final String json = _versioned(kind)
          ? editorSyncWithRevision(kind, wire, 1)
          : wire;
      documents.add(
        SyncDocumentChange.put(kind: kind, id: id, baseHash: null, json: json),
      );
    }
    for (final SyncBaseDocument base in binding.documents.values) {
      if (!base.editable || claimed.contains(base.key)) continue;
      if (_serverMoved(base.key, base) ||
          binding.conflicts.containsKey(base.key) ||
          !_creatable(base.key)) {
        continue;
      }
      final WorkspaceDoc? doc = _workspace.byId(base.docId);
      if (doc != null && _memo.of(doc).key == base.key) continue;
      documents.add(
        SyncDocumentChange.delete(
          kind: base.kind,
          id: base.id,
          baseHash: base.hash,
        ),
      );
    }
    documents.sort(
      (SyncDocumentChange a, SyncDocumentChange b) => a.key.compareTo(b.key),
    );

    final List<SyncImageChange> imageChanges = <SyncImageChange>[];
    final bool mayUpload = token?.has('publish:images') ?? false;
    if (mayUpload) {
      for (final StoredImage image in List<StoredImage>.of(images.images)) {
        final SyncBaseImage? base = binding.images[image.path];
        if (base != null && !base.local) continue;
        if (base == null && _serverImages.containsKey(image.path)) continue;
        final String hash = _memo.imageHash(image);
        if (base != null) {
          final SyncImageRow? row = _serverImages[image.path];
          if (hash == base.hash) continue;
          if (_catalogLoaded && (row == null || row.hash != base.hash)) {
            continue;
          }
        }
        final Uint8List? bytes = decodeDataUriBytes(image.dataUri);
        final StoredImageData? decoded = decodeSupportedImageData(
          image.dataUri,
        );
        if (bytes == null || decoded == null) continue;
        imageChanges.add(
          SyncImageChange.put(
            path: image.path,
            baseHash: base?.hash,
            hash: hash,
            mediaType: _mediaType(image.dataUri),
            bytes: bytes,
          ),
        );
      }
      for (final SyncBaseImage base in binding.images.values) {
        if (!base.local || images.contains(base.path)) continue;
        final SyncImageRow? row = _serverImages[base.path];
        if (_catalogLoaded && (row == null || row.hash != base.hash)) continue;
        imageChanges.add(
          SyncImageChange.delete(path: base.path, baseHash: base.hash),
        );
      }
    }
    imageChanges.sort(
      (SyncImageChange a, SyncImageChange b) => a.path.compareTo(b.path),
    );
    return SyncChangeset(documents: documents, images: imageChanges);
  }

  bool _versioned(String kind) =>
      _meta?.isVersioned(kind) ??
      (kind != 'menu' && kind != 'container-preview');

  String _mediaType(String dataUri) {
    final int semicolon = dataUri.indexOf(';');
    return dataUri.startsWith('data:') && semicolon > 5
        ? dataUri.substring(5, semicolon)
        : 'image/png';
  }

  String _newIdempotencyKey() {
    const String alphabet =
        'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-';
    return 'pub_${List<String>.generate(20, (int _) => alphabet[_random.nextInt(alphabet.length)]).join()}';
  }

  /// [changeset] cut into publishes that fit one request: images first,
  /// then documents, each part under the path's size in UTF-8 bytes (as
  /// sent, escapes included) and entry limits.
  List<SyncChangeset> _parts(SyncChangeset changeset) {
    final int limit =
        (connectionStatus.path == ConnectionPath.relay
            ? relayBodyBytes
            : _directPublishBytes) -
        _publishEnvelopeBytes;
    final List<SyncChangeset> parts = <SyncChangeset>[];
    List<SyncImageChange> images = <SyncImageChange>[];
    List<SyncDocumentChange> documents = <SyncDocumentChange>[];
    int bytes = 0;
    void cut() {
      if (images.isEmpty && documents.isEmpty) return;
      parts.add(SyncChangeset(documents: documents, images: images));
      images = <SyncImageChange>[];
      documents = <SyncDocumentChange>[];
      bytes = 0;
    }

    for (final SyncImageChange change in changeset.images) {
      final int size = utf8Length(jsonEncode(change.toJson())) + 1;
      if (images.length >= huiEditorSyncMaxPublishImages ||
          (bytes + size > limit && images.isNotEmpty)) {
        cut();
      }
      images.add(change);
      bytes += size;
    }
    for (final SyncDocumentChange change in changeset.documents) {
      final int size = utf8Length(jsonEncode(change.toJson())) + 1;
      if (documents.length >= _publishDocuments ||
          (bytes + size > limit &&
              (documents.isNotEmpty || images.isNotEmpty))) {
        cut();
      }
      documents.add(change);
      bytes += size;
    }
    cut();
    return parts;
  }

  /// [part] in two, for a path that refused it as too large; null for a
  /// single entry.
  List<SyncChangeset>? _halves(SyncChangeset part) {
    if (part.length < 2) return null;
    if (part.images.length >= 2) {
      final int half = part.images.length ~/ 2;
      return <SyncChangeset>[
        SyncChangeset(images: part.images.sublist(0, half)),
        SyncChangeset(
          images: part.images.sublist(half),
          documents: part.documents,
        ),
      ];
    }
    if (part.images.length == 1 && part.documents.isNotEmpty) {
      return <SyncChangeset>[
        SyncChangeset(images: part.images),
        SyncChangeset(documents: part.documents),
      ];
    }
    final int half = part.documents.length ~/ 2;
    return <SyncChangeset>[
      SyncChangeset(documents: part.documents.sublist(0, half)),
      SyncChangeset(documents: part.documents.sublist(half)),
    ];
  }

  /// Documents and images this pairing reaches, by the catalog.
  int get _reached => _serverDocs.length + _serverImages.length;

  /// Asks before deleting [deletes] of the [total] documents and images on
  /// the server. The bar names the question while it is open.
  Future<bool> _confirmDeletes(int deletes, int total) async {
    String question() => huiText(
      'Delete {count} of {total} documents and images on the server?',
      <String, Object?>{'count': deletes, 'total': total},
    );
    _question = question;
    _notify();
    try {
      return await confirm(
        title: question(),
        message: huiText('Review changes lists each one under Deleted.'),
        confirmLabel: huiText('Delete {count}', <String, Object?>{
          'count': deletes,
        }),
        destructive: true,
      );
    } finally {
      _question = null;
      _notify();
    }
  }

  /// Sends what changed here: new images uploaded first, then the
  /// changeset (in parts when it is large). Each part is recorded before it
  /// is sent, so an answer lost to a dropped connection is asked for again
  /// with the same idempotency key once the server answers. A publish that
  /// deletes many documents asks first, naming the count.
  Future<void> publish() async {
    if (_publishing ||
        !_catalogLoaded ||
        _readingCatalog ||
        !canPublishEditorSyncStatus(status)) {
      return;
    }
    _publishing = true;
    _problem = null;
    _outcome = null;
    _outcomeMessage = null;
    _notify();
    try {
      store.flushAutosave(notify: false);
      await _workspace.writesSettled;
      // Asked before anything is sent or locked, so events keep landing
      // while the question is open.
      final SyncChangeset asked = buildChangeset();
      if (asked.isEmpty) {
        toast(huiText('Nothing to publish.'));
        return;
      }
      int? agreed;
      if (editorSyncDeletesNeedConfirm(asked.deletes, _reached)) {
        if (!await _confirmDeletes(asked.deletes, _reached)) return;
        agreed = asked.deletes;
      }
      await _locked(() async {
        final SyncChangeset changeset = buildChangeset();
        if (changeset.isEmpty) {
          toast(huiText('Nothing to publish.'));
          return;
        }
        // The yes covers the count the owner saw; a changeset that moved
        // since then is confirmed only through the server's own question.
        final bool confirmed = agreed == changeset.deletes;
        final EditorApi api = _api;
        for (final SyncImageChange change in changeset.images) {
          if (change.delete) continue;
          await api.uploadImage(change.mediaType!, change.bytes!);
        }
        final _PublishTally tally = _PublishTally(changeset.length);
        bool refusedWhole = false;
        final List<SyncChangeset> queue = _parts(changeset);
        while (queue.isNotEmpty) {
          final SyncChangeset part = queue.removeAt(0);
          final String key = _newIdempotencyKey();
          final Map<String, Object?> body = part.toJson(
            key,
            confirmDeletes: confirmed && part.deletes > 0 ? part.deletes : null,
          );
          final SyncPublishResult? result;
          try {
            result = await _send(api, body);
          } on EditorSyncFailure catch (refused) {
            // Nothing of a part the path found too large was applied; its
            // halves go instead, down to a single entry.
            final List<SyncChangeset>? halves = refused.code == 'too-large'
                ? _halves(part)
                : null;
            if (halves != null) {
              queue.insertAll(0, halves);
              continue;
            }
            // Before any answer the publish failed whole, as one part does.
            if (!tally.anyAnswered || refused.ended) rethrow;
            tally.stopped(refused.message);
            refusedWhole = _refusedWhole(refused);
            break;
          }
          if (result == null) {
            if (!tally.anyAnswered) return;
            tally.stopped('');
            break;
          }
          await _settle(result, body);
          tally.answered(part.length, result);
        }
        final String outcome = refusedWhole || tally.rejected
            ? 'rejected'
            : tally.conflict
            ? 'conflict'
            : 'applied';
        _outcome = outcome;
        _outcomeMessage = tally.message;
        _announce(outcome, _outcomeMessage!, failed: tally.failed);
      });
    } on EditorSyncFailure catch (error) {
      if (!error.ended) {
        if (_refusedWhole(error)) {
          _outcome = 'rejected';
          _outcomeMessage = error.message;
        } else {
          _problem = () => error.message;
        }
        toast(error.message, error: true);
      }
    } finally {
      _publishing = false;
      _changeStamp = -1;
      _notify();
    }
  }

  /// Sends one publish [body], recorded first so an answer lost to a
  /// dropped connection is asked for again. The server refuses many
  /// deletions without `confirmDeletes` (`428 confirm-deletes`, nothing
  /// written): the owner is asked with the server's count, and a yes sends
  /// the same key again with it. Null when the owner says no.
  Future<SyncPublishResult?> _send(
    EditorApi api,
    Map<String, Object?> body,
  ) async {
    Map<String, Object?> sending = body;
    while (true) {
      _binding.pending = SyncPendingPublish(
        sending['idempotencyKey']! as String,
        sending,
      );
      _persist();
      try {
        final SyncPublishResult result = await api.publish(sending);
        _binding.pending = null;
        return result;
      } on EditorSyncFailure catch (refused) {
        if (refused.code != 'confirm-deletes') {
          // Refused whole, nothing written: sending it again changes
          // nothing, and the changes stay here as edits.
          if (_refusedWhole(refused)) {
            _binding.pending = null;
            _persist();
          }
          rethrow;
        }
        _binding.pending = null;
        _persist();
        final int? deletes = refused.errorCount('deletes');
        if (deletes == null || sending['confirmDeletes'] == deletes) rethrow;
        if (!await _confirmDeletes(
          deletes,
          refused.errorCount('total') ?? _reached,
        )) {
          return null;
        }
        sending = <String, Object?>{...sending, 'confirmDeletes': deletes};
      }
    }
  }

  /// Whether the server, or the relay before it, refused a publish whole:
  /// a client error that no resend of the same body gets past. A token
  /// error ends the pairing instead; a busy server, a rate limit or a lost
  /// answer may still have it land.
  bool _refusedWhole(EditorSyncFailure refused) {
    if (refused.code == 'too-large') return true;
    final int status = refused.status;
    return status >= 400 &&
        status < 500 &&
        !refused.ended &&
        status != 401 &&
        status != 408 &&
        status != 429;
  }

  /// A publish whose answer was lost goes again with its key: the server
  /// answers the first outcome, or applies it once (a body equal to what it
  /// holds counts as landed). Runs inside the resync lock.
  /// Returns whether it left an outcome for the bar.
  Future<bool> _resendPending(EditorApi api) async {
    final SyncPendingPublish? pending = _binding.pending;
    if (pending == null) return false;
    final SyncPublishResult? result;
    try {
      result = await _send(api, pending.body);
    } on EditorSyncFailure catch (refused) {
      if (!_refusedWhole(refused)) rethrow;
      _outcome = 'rejected';
      _outcomeMessage = refused.message;
      toast(refused.message, error: true);
      return true;
    }
    // Declined: nothing was sent, and the changes stay here to review.
    if (result == null) return false;
    await _settle(result, pending.body);
    _outcome = result.status;
    _outcomeMessage = result.message;
    _announce(result.status, result.message);
    _persist();
    return true;
  }

  void _announce(String outcome, String message, {bool failed = false}) {
    switch (outcome) {
      case 'applied':
        toast(
          message.isEmpty ? huiText('Published to the server.') : message,
          error: failed,
        );
      case 'conflict':
        toast(
          message.isEmpty
              ? huiText('Nothing landed: the server copies moved.')
              : message,
          error: true,
        );
      case 'rejected':
        toast(
          message.isEmpty
              ? huiText('The server refused the publish.')
              : message,
          error: true,
        );
    }
  }

  /// Takes the answer to the publish [body]: refused keys become conflicts
  /// next to the server copy, and the keys it sent take the server's hash
  /// and revision (the local copy's revision moves with it, so it reads
  /// unchanged). Any other row the answer carries was changed elsewhere and
  /// is read the way a server event is.
  Future<void> _settle(
    SyncPublishResult result,
    Map<String, Object?> body,
  ) async {
    final EditorSyncBinding binding = _binding;
    if (result.status == 'rejected') return;
    final Set<String> sentDocuments = <String>{};
    final Set<String> sentImages = <String>{};
    final Object? documents = body['documents'];
    final Object? imageEntries = body['images'];
    for (final Object? entry
        in documents is List ? documents : const <Object?>[]) {
      if (entry is Map && entry['kind'] is String && entry['id'] is String) {
        sentDocuments.add(
          editorSyncKey(entry['kind'] as String, entry['id'] as String),
        );
      }
    }
    for (final Object? entry
        in imageEntries is List ? imageEntries : const <Object?>[]) {
      if (entry is Map && entry['path'] is String) {
        sentImages.add(entry['path'] as String);
      }
    }
    // Typing still in the editor reaches the workspace before any copy is
    // compared with it or rebased.
    store.flushAutosave(notify: false);
    final SyncCatalog delta = result.delta;
    final SyncCatalog elsewhere = SyncCatalog(
      documents: <SyncDocumentRow>[
        for (final SyncDocumentRow row in delta.documents)
          if (!sentDocuments.contains(row.key)) row,
      ],
      deleted: <String>[
        for (final String key in delta.deleted)
          if (!sentDocuments.contains(key)) key,
      ],
      images: <SyncImageRow>[
        for (final SyncImageRow row in delta.images)
          if (!sentImages.contains(row.path)) row,
      ],
      removedImages: <String>[
        for (final String path in delta.removedImages)
          if (!sentImages.contains(path)) path,
      ],
    );
    for (final SyncConflict conflict in result.conflicts) {
      binding.conflicts[conflict.kind == 'image'
              ? editorSyncImageKey(conflict.id)
              : editorSyncKey(conflict.kind, conflict.id)] =
          conflict.reason;
    }
    final List<WorkspaceDocumentUpdate> rebases = <WorkspaceDocumentUpdate>[];
    for (final SyncDocumentRow row in delta.documents) {
      if (!sentDocuments.contains(row.key)) continue;
      _serverDocs[row.key] = row;
      binding.conflicts.remove(row.key);
      final WorkspaceDoc? doc = _docForKey(row.key);
      binding.documents[row.key] = _baseFor(
        row,
        null,
        key: row.key,
        docId: doc?.id,
      );
      final int? revision = row.revision;
      if (doc != null && revision != null) {
        final String json = editorSyncWithRevision(
          row.kind,
          doc.json,
          revision,
        );
        if (json != doc.json) {
          rebases.add(WorkspaceDocumentUpdate(documentId: doc.id, json: json));
        }
      }
    }
    for (final String key in delta.deleted) {
      if (!sentDocuments.contains(key)) continue;
      _serverDocs.remove(key);
      binding.documents.remove(key);
      binding.conflicts.remove(key);
    }
    for (final SyncImageRow row in delta.images) {
      if (!sentImages.contains(row.path)) continue;
      _serverImages[row.path] = row;
      binding.images[row.path] = SyncBaseImage.fromRow(
        row,
        local: images.contains(row.path),
      );
      binding.conflicts.remove(editorSyncImageKey(row.path));
    }
    for (final String path in delta.removedImages) {
      if (!sentImages.contains(path)) continue;
      _serverImages.remove(path);
      binding.images.remove(path);
      binding.conflicts.remove(editorSyncImageKey(path));
    }
    if (rebases.isNotEmpty) {
      final String? active = _workspace.activeId;
      _workspace.updateDocuments(rebases);
      if (active != null &&
          rebases.any((WorkspaceDocumentUpdate u) => u.documentId == active)) {
        store.openDocument(active, refresh: true);
      }
    }
    _touchBinding();
    _persist();
    if (!elsewhere.isEmpty) await _applyCatalog(elsewhere, full: false);
  }

  // --- conflicts ----------------------------------------------------------

  /// A choice moves on from the last publish: its line no longer describes
  /// the workspace.
  void _forgetOutcome() {
    _outcome = null;
    _outcomeMessage = null;
  }

  /// Keeps this tab's copy of [key]: it now starts from the server copy, so
  /// the next Publish overwrites the server.
  Future<void> keepMine(String key) async {
    final EditorSyncBinding binding = _binding;
    _forgetOutcome();
    if (editorSyncKeyKind(key) == 'image' && !_serverDocs.containsKey(key)) {
      final String path = editorSyncKeyId(key);
      final SyncImageRow? row = _serverImages[path];
      if (row == null) {
        binding.images.remove(path);
      } else {
        binding.images[path] = SyncBaseImage.fromRow(row, local: true);
      }
      binding.conflicts.remove(key);
      _touchBinding();
      _persist();
      _notify();
      return;
    }
    final SyncDocumentRow? row = _serverDocs[key];
    final WorkspaceDoc? doc = _docForKey(key);
    final SyncBaseDocument? base = binding.documents[key];
    if (row == null) {
      binding.documents.remove(key);
    } else if (doc == null) {
      // Deleted here: the deletion now starts from the server copy, so the
      // next Publish deletes it.
      if (base != null) {
        binding.documents[key] = base.copyWith(
          hash: row.hash,
          revision: row.revision,
          name: row.name,
          size: row.size,
        );
      }
    } else {
      binding.documents[key] = _baseFor(row, null, key: key, docId: doc.id);
      final int? revision = row.revision;
      if (revision != null) {
        final String json = editorSyncWithRevision(
          row.kind,
          doc.json,
          revision,
        );
        if (json != doc.json) {
          store.flushAutosave(notify: false);
          _workspace.updateDocuments(<WorkspaceDocumentUpdate>[
            WorkspaceDocumentUpdate(documentId: doc.id, json: json),
          ]);
          if (_workspace.activeId == doc.id) {
            store.openDocument(doc.id, refresh: true);
          }
        }
      }
    }
    binding.conflicts.remove(key);
    _touchBinding();
    _persist();
    _notify();
  }

  /// Takes the server copy of [key] and drops this tab's version.
  Future<void> useServer(String key) async {
    final EditorSyncBinding binding = _binding;
    _forgetOutcome();
    try {
      if (editorSyncKeyKind(key) == 'image' && !_serverDocs.containsKey(key)) {
        final String path = editorSyncKeyId(key);
        final SyncImageRow? row = _serverImages[path];
        if (row == null) {
          images.remove(path);
          binding.images.remove(path);
        } else {
          binding.images[path] = SyncBaseImage.fromRow(row);
        }
        binding.conflicts.remove(key);
        _touchBinding();
        _persist();
        unawaited(_fetchImages());
        _notify();
        return;
      }
      await _locked(() async {
        final SyncDocumentRow? row = _serverDocs[key];
        final WorkspaceDoc? doc = _docForKey(key);
        if (row == null) {
          if (doc != null) {
            await _applyWrites(const <SyncDocWrite>[], <String>{doc.id});
          }
          binding.documents.remove(key);
        } else {
          final List<SyncDocumentBody> bodies = (await _api.readDocuments(
            <String>[key],
          )).bodies;
          if (bodies.isEmpty) return;
          final SyncDocumentBody body = bodies.single;
          final Map<String, String> landed = await _applyWrites(<SyncDocWrite>[
            SyncDocWrite(
              key: key,
              json: body.json,
              title: doc?.title ?? _titleFor(key),
              docId: doc?.id,
            ),
          ], const <String>{});
          final String? docId = landed[key];
          binding.documents[key] = _baseFor(row, body, key: key, docId: docId);
          final WorkspaceDoc? written = docId == null
              ? null
              : _workspace.byId(docId);
          if (written != null) _memo.seed(written, key, body.hash);
        }
        binding.conflicts.remove(key);
        _touchBinding();
        _persist();
      });
    } on EditorSyncFailure catch (error) {
      toast(error.message, error: true);
      _failed(error);
    }
    _notify();
  }

  WorkspaceDoc? _docForKey(String key) {
    final String? docId = _binding.documents[key]?.docId;
    final WorkspaceDoc? bound = _workspace.byId(docId);
    if (bound != null && _memo.of(bound).key == key) return bound;
    for (final WorkspaceDoc doc in _workspace.docs) {
      if (_memo.of(doc).key == key) return doc;
    }
    return null;
  }

  /// Opens the workspace document a server finding or change names.
  bool openDocument(String kind, String id) {
    final WorkspaceDoc? doc = _docForKey(editorSyncKey(kind, id));
    if (doc == null) return false;
    store.openDocument(doc.id);
    return true;
  }

  // --- history --------------------------------------------------------------

  Future<String?> historyVersion(SyncHistoryVersion version) => _api
      .history(version.kind, version.id, version.version)
      .catchError((Object error) {
        if (error is EditorSyncFailure) _failed(error);
        throw error;
      });

  /// Restores a stored version into the open document as one undoable edit.
  /// Returns why it could not, or null.
  String? restoreHistoryVersion(SyncHistoryVersion version, String json) {
    if (store.menuId != version.id) {
      return huiText(
        'Open that document before restoring one of its versions.',
      );
    }
    store.importJson('${version.id}.json', json);
    closeServer();
    return null;
  }
}

/// What a publish sent in parts reports once. One part keeps the server's
/// own words; several report the total that landed, then one line for the
/// changes that did not.
final class _PublishTally {
  _PublishTally(this.total);

  /// Entries in the whole changeset.
  final int total;

  int _parts = 0;
  int _answered = 0;
  int _landed = 0;
  String _said = '';
  String? _failure;
  bool _stopped = false;

  /// Entries that kept the server copy.
  int kept = 0;

  /// A part was rejected whole.
  bool rejected = false;

  /// A part answered `conflict`.
  bool conflict = false;

  /// The server answered a part, so the changes before a failure stand.
  bool get anyAnswered => _parts > 0;

  /// Some changes did not publish.
  bool get failed => _failure != null;

  /// The server's answer to a part of [entries] changes.
  void answered(int entries, SyncPublishResult result) {
    _parts++;
    _said = result.message;
    if (result.status == 'rejected') {
      rejected = true;
      _failure ??= result.message;
      return;
    }
    conflict = conflict || result.status == 'conflict';
    _answered += entries;
    kept += result.conflicts.length;
    _landed += entries - result.conflicts.length;
  }

  /// A later part failed with [reason] (empty when the owner declined it),
  /// and the parts after it were not sent.
  void stopped(String reason) {
    _stopped = true;
    _failure ??= reason;
  }

  String get message {
    if (_parts == 1 && !_stopped) return _said;
    final String? failure = _failure;
    return <String>[
      if (_landed > 0)
        huiPlural(
          'sync.published_changes',
          _landed,
          oneEnglish: 'Published {count} change.',
          otherEnglish: 'Published {count} changes.',
        )
      else
        huiText('Nothing was published.'),
      if (kept > 0)
        huiPlural(
          'sync.kept_server_copy',
          kept,
          oneEnglish: '{count} kept the server copy.',
          otherEnglish: '{count} kept the server copy.',
        ),
      if (failure != null && _answered > 0)
        huiPlural(
          'sync.changes_not_published',
          total - _answered,
          oneEnglish: 'The other {count} change did not publish.',
          otherEnglish: 'The other {count} changes did not publish.',
        ),
      if (failure != null && failure.isNotEmpty) _reason(failure),
    ].join(' ');
  }

  /// A part's refusal without the server's lead-in, which the line before
  /// it already says.
  static String _reason(String said) {
    const String lead = 'Nothing was published: ';
    if (!said.startsWith(lead) || said.length == lead.length) return said;
    final String rest = said.substring(lead.length);
    return '${rest[0].toUpperCase()}${rest.substring(1)}';
  }
}
