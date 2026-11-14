part of 'editor_sync_controller.dart';

/// More new server documents than this in one catalog read are listed by
/// name and filled in the background; fewer are read at once.
const int _readNowLimit = 40;

/// The background fill's first page is about this share of what is left,
/// so the count moves early instead of one jump at the end; the
/// rest come in the path's full pages, each landing costing a workspace
/// write.
const int _fillFirstShare = 8;

/// The smallest first page the fill asks for.
const int _fillMinPageBytes = 64 * 1024;

/// Reading the server into the workspace: the catalog, the background fill,
/// documents opened before their body arrived, server events, images, meta
/// and notes.
extension EditorSyncLoading on EditorSyncController {
  /// After every connect: a publish whose answer was lost goes first, then
  /// the whole catalog, then whatever it says to read.
  Future<void> _resync(int session) async {
    if (_readingCatalog) {
      // The read under way may belong to a session that just ended: this
      // one reads again once it is done.
      _resyncAgain = true;
      return;
    }
    _listedOnce = _catalogLoaded;
    _readingCatalog = true;
    _notify();
    try {
      await _locked(() async {
        if (session != _session || _disposed) return;
        final EditorApi api = _api;
        final bool answered = await _resendPending(api);
        final SyncCatalog catalog = await api.catalog();
        if (session != _session || _disposed) return;
        await _applyCatalog(catalog, full: true);
        hub.noteCounts(
          serverId,
          documents: catalog.documents.length,
          images: catalog.images.length,
        );
        await _readSections(api, catalog.metaHash, catalog.notesHash);
        _problem = null;
        // The bar describes the server as just read, unless this read
        // answered a publish of its own.
        if (!answered) _forgetOutcome();
      });
    } on EditorSyncFailure catch (error) {
      _failed(error);
    } on SyncMalformed {
      _problem = () => transportCopy('dropped').text;
    } finally {
      _readingCatalog = false;
      _notify();
    }
    if (_disposed) return;
    if (_resyncAgain) {
      _resyncAgain = false;
      unawaited(_resync(_session));
      return;
    }
    if (session == _session) {
      unawaited(_fill());
      unawaited(_fetchImages());
    }
  }

  /// Meta and notes, read again only when their hash moved.
  Future<void> _readSections(
    EditorApi api,
    String? metaHash,
    String? notesHash,
  ) async {
    if (_meta == null || (metaHash != null && metaHash != _meta!.hash)) {
      _meta = await api.meta();
      _binding.metaHash = _meta!.hash;
    }
    if (notesHash == null || notesHash != _notes.hash) {
      try {
        _notes = await api.notes();
        _binding.notesHash = _notes.hash;
      } on EditorSyncFailure {
        _notes = SyncNotes.empty;
      }
    }
  }

  void _onEvent(Map<String, Object?> event) {
    if (_disposed) return;
    switch (event['type']) {
      case 'catalog':
        final SyncCatalog delta;
        try {
          delta = SyncCatalog.decode(event);
        } on SyncMalformed {
          return;
        }
        final int session = _session;
        unawaited(_absorbEvent(delta, session));
      case 'open':
        _notify();
    }
  }

  Future<void> _absorbEvent(SyncCatalog delta, int session) async {
    try {
      await _locked(() async {
        if (session != _session || _disposed) return;
        if (!delta.isEmpty) await _applyCatalog(delta, full: false);
        if (delta.metaHash != null || delta.notesHash != null) {
          await _readSections(_api, delta.metaHash, delta.notesHash);
        }
      });
    } on EditorSyncFailure catch (error) {
      _failed(error);
    }
    _notify();
    if (session == _session) {
      unawaited(_fill());
      if (delta.images.isNotEmpty) unawaited(_fetchImages());
    }
  }

  SyncBaseDocument _baseFor(
    SyncDocumentRow? row,
    SyncDocumentBody? body, {
    required String key,
    String? docId,
    bool loaded = true,
  }) => SyncBaseDocument(
    kind: editorSyncKeyKind(key),
    id: editorSyncKeyId(key),
    hash: body?.hash ?? row!.hash,
    name: row?.name ?? editorSyncKeyId(key).split('/').last,
    revision: body?.revision ?? row?.revision,
    docId: docId,
    size: row?.size ?? body?.json.length ?? 0,
    loaded: loaded,
  );

  String _titleFor(String key) =>
      _serverDocs[key]?.name ?? editorSyncKeyId(key).split('/').last;

  List<String> get _menuIds => <String>[
    for (final SyncDocumentRow row in _serverDocs.values)
      if (row.kind == 'menu') row.id,
  ];

  List<String> get _imagePaths => _serverImages.keys.toList()..sort();

  Map<String, WorkspaceDoc> _unboundByKey() {
    final Map<String, WorkspaceDoc> byKey = <String, WorkspaceDoc>{};
    for (final WorkspaceDoc doc in _workspace.docs) {
      if (_baseKeyByDocId.containsKey(doc.id)) continue;
      final String? key = _memo.of(doc).key;
      if (key != null) byKey.putIfAbsent(key, () => doc);
    }
    return byKey;
  }

  /// Applies a catalog (whole) or a catalog event: untouched documents take
  /// the server copy, edited ones stay and read "Changed on server", new
  /// ones are listed (by name with a blank body when there are many) and
  /// removed ones go unless edited here.
  Future<void> _applyCatalog(SyncCatalog catalog, {required bool full}) async {
    final EditorSyncBinding binding = _binding;
    store.flushAutosave(notify: false);
    await _workspace.writesSettled;
    final Set<String> touched = <String>{};
    final Set<String> touchedImages = <String>{};
    if (full) {
      _serverDocs.clear();
      _serverImages.clear();
      _unreadable.clear();
      touched.addAll(binding.documents.keys);
      touchedImages.addAll(binding.images.keys);
    }
    for (final SyncDocumentRow row in catalog.documents) {
      _serverDocs[row.key] = row;
      touched.add(row.key);
    }
    for (final String key in catalog.deleted) {
      _serverDocs.remove(key);
      touched.add(key);
    }
    for (final SyncImageRow row in catalog.images) {
      _serverImages[row.path] = row;
      touchedImages.add(row.path);
    }
    for (final String path in catalog.removedImages) {
      _serverImages.remove(path);
      touchedImages.add(path);
    }
    if (full) _catalogLoaded = true;
    final bool replace =
        full &&
        binding.documents.isEmpty &&
        editorSyncIsSampleWorkspace(_workspace, images);

    Map<String, WorkspaceDoc>? unbound;
    final List<String> fresh = <String>[];
    final Set<String> needBodies = <String>{};
    final Set<String> deletes = <String>{
      if (replace)
        for (final WorkspaceDoc doc in _workspace.docs) doc.id,
    };
    // Bound documents the server removed, by key: they go once the bodies
    // are read, unless edited meanwhile.
    final Map<String, String> removed = <String, String>{};
    for (final String key in touched) {
      final SyncDocumentRow? row = _serverDocs[key];
      final SyncBaseDocument? base = binding.documents[key];
      if (row == null) {
        if (base == null) continue;
        final WorkspaceDoc? doc = _workspace.byId(base.docId);
        if (doc == null) {
          binding.documents.remove(key);
          binding.conflicts.remove(key);
          continue;
        }
        if (!base.loaded || _memo.of(doc).hash == base.hash) {
          removed[key] = doc.id;
        }
        continue;
      }
      if (DocumentTypeRegistry.byWireKind(row.kind) == null) {
        binding.documents[key] = _baseFor(row, null, key: key);
        continue;
      }
      if (base != null && base.hash == row.hash) {
        binding.documents[key] = base.copyWith(
          name: row.name,
          revision: row.revision,
          size: row.size,
        );
        continue;
      }
      if (base == null) {
        unbound ??= replace ? <String, WorkspaceDoc>{} : _unboundByKey();
        final WorkspaceDoc? local = unbound[key];
        if (local == null) {
          fresh.add(key);
        } else if (_memo.of(local).hash == row.hash) {
          binding.documents[key] = _baseFor(
            row,
            null,
            key: key,
            docId: local.id,
          );
        } else if (local.json == editorSyncBlankJson(row.kind)) {
          // A blank listed from the catalog whose binding row was never
          // stored: it is filled, never read as an edit.
          binding.documents[key] = _baseFor(
            row,
            null,
            key: key,
            docId: local.id,
            loaded: false,
          );
        }
        // A local document under a server key that differs: it reads
        // "Changed on server" until the owner picks a copy.
        continue;
      }
      final WorkspaceDoc? doc = _workspace.byId(base.docId);
      if (doc == null) continue;
      if (!base.loaded) {
        binding.documents[key] = base.copyWith(
          hash: row.hash,
          revision: row.revision,
          name: row.name,
          size: row.size,
        );
        continue;
      }
      final String? local = _memo.of(doc).hash;
      if (local == row.hash) {
        binding.documents[key] = base.copyWith(
          hash: row.hash,
          revision: row.revision,
          name: row.name,
          size: row.size,
        );
        binding.conflicts.remove(key);
        continue;
      }
      if (local == base.hash) needBodies.add(key);
    }

    final List<SyncDocWrite> writes = <SyncDocWrite>[];
    final Set<String> stubs = <String>{};
    final bool listByName = replace || fresh.length > _readNowLimit;
    for (final String key in fresh) {
      final String? blank = listByName
          ? editorSyncBlankJson(editorSyncKeyKind(key))
          : null;
      if (blank == null) {
        needBodies.add(key);
        continue;
      }
      stubs.add(key);
      writes.add(SyncDocWrite(key: key, json: blank, title: _titleFor(key)));
    }
    List<SyncDocumentBody> bodies = const <SyncDocumentBody>[];
    if (needBodies.isNotEmpty) {
      bodies = (await _api.readDocuments(needBodies)).bodies;
    }
    // Typing during the read reaches the workspace first; a document edited
    // since it was found untouched keeps the edit and reads "Changed on
    // server".
    if (needBodies.isNotEmpty || removed.isNotEmpty) {
      store.flushAutosave(notify: false);
    }
    bool editedSince(String key) {
      final SyncBaseDocument? base = binding.documents[key];
      if (base == null || !base.loaded) return false;
      final WorkspaceDoc? doc = _workspace.byId(base.docId);
      return doc != null && _memo.of(doc).hash != base.hash;
    }

    for (final MapEntry<String, String> entry in removed.entries) {
      if (editedSince(entry.key)) continue;
      deletes.add(entry.value);
      binding.documents.remove(entry.key);
      binding.conflicts.remove(entry.key);
    }
    bodies = <SyncDocumentBody>[
      for (final SyncDocumentBody body in bodies)
        if (!editedSince(body.key)) body,
    ];
    if (bodies.isNotEmpty) {
      for (final SyncDocumentBody body in bodies) {
        writes.add(
          SyncDocWrite(
            key: body.key,
            json: body.json,
            title: _titleFor(body.key),
            docId: binding.documents[body.key]?.docId,
          ),
        );
      }
    }
    final Map<String, String> landed = await _applyWrites(writes, deletes);
    for (final String key in stubs) {
      final String? docId = landed[key];
      if (docId == null) continue;
      binding.documents[key] = _baseFor(
        _serverDocs[key],
        null,
        key: key,
        docId: docId,
        loaded: false,
      );
    }
    for (final SyncDocumentBody body in bodies) {
      final String? docId = landed[body.key];
      binding.documents[body.key] = _baseFor(
        _serverDocs[body.key],
        body,
        key: body.key,
        docId: docId,
      );
      binding.conflicts.remove(body.key);
      final WorkspaceDoc? doc = docId == null ? null : _workspace.byId(docId);
      if (doc != null) _memo.seed(doc, body.key, body.hash);
    }

    final List<String> removeImages = <String>[];
    for (final String path in touchedImages) {
      final SyncImageRow? row = _serverImages[path];
      final SyncBaseImage? base = binding.images[path];
      if (row == null) {
        if (base == null) continue;
        final StoredImage? local = images.byPath(path);
        if (local == null ||
            !base.local ||
            _memo.imageHash(local) == base.hash) {
          if (local != null && base.local) removeImages.add(path);
          binding.images.remove(path);
        }
        continue;
      }
      if (base == null) {
        final StoredImage? local = images.byPath(path);
        binding.images[path] = SyncBaseImage.fromRow(
          row,
          local: local != null && _memo.imageHash(local) == row.hash,
        );
        continue;
      }
      if (base.hash == row.hash) continue;
      final StoredImage? local = images.byPath(path);
      final String? localHash = local == null ? null : _memo.imageHash(local);
      if (localHash == row.hash) {
        binding.images[path] = SyncBaseImage.fromRow(row, local: true);
        binding.conflicts.remove(editorSyncImageKey(path));
      } else if (!base.local || localHash == base.hash) {
        binding.images[path] = SyncBaseImage.fromRow(row);
      }
    }
    for (final String path in removeImages) {
      images.remove(path);
    }
    _touchBinding();
    _persist();
  }

  /// Writes server bodies into the workspace in one save, keeping the
  /// open document unless it was deleted. The live documents change in
  /// place, so nothing typed meanwhile is undone. Returns where each key
  /// landed.
  Future<Map<String, String>> _applyWrites(
    List<SyncDocWrite> writes,
    Set<String> deletes,
  ) async {
    if (writes.isEmpty && deletes.isEmpty) return const <String, String>{};
    final String? active = _workspace.activeId;
    final ({
      List<WorkspaceFolder> folders,
      List<WorkspaceDoc> documents,
      List<WorkspaceDocumentUpdate> updates,
      Map<String, String> docIds,
    })
    batch = editorSyncBatch(
      workspace: _workspace,
      writes: writes,
      removed: deletes,
      menuIds: _menuIds,
      imagePaths: _imagePaths,
    );
    if (!_workspace.applyBatch(
      folders: batch.folders,
      documents: batch.documents,
      updates: batch.updates,
      removals: deletes,
    )) {
      throw EditorSyncFailure(
        _workspace.lastError ??
            huiText('The server changes could not be stored locally.'),
        code: 'storage',
      );
    }
    if (batch.documents.isNotEmpty) {
      onArrived(<ArrivedDocument>[
        for (final WorkspaceDoc doc in batch.documents)
          (id: doc.id, kind: doc.kind),
      ]);
    }
    final String? nowActive = _workspace.activeId;
    if (nowActive != null &&
        (nowActive != active || batch.docIds.containsValue(nowActive))) {
      store.openDocument(nowActive, refresh: true);
    }
    await _workspace.writesSettled;
    return batch.docIds;
  }

  /// Reads the documents listed from the catalog whose body is not here
  /// yet, in big pages, writing them into the workspace every [landEvery].
  Future<void> _fill() async {
    if (_filling) {
      _fillAgain = true;
      return;
    }
    _filling = true;
    final int session = _session;
    try {
      do {
        _fillAgain = false;
        final List<String> unloaded = <String>[
          for (final SyncBaseDocument row in _binding.documents.values)
            if (row.editable && !row.loaded && !_unreadable.contains(row.key))
              row.key,
        ];
        final int left = unloaded.length;
        if (left == 0) break;
        // The count runs over every listed document, as the library shows
        // them, and moves as bodies arrive.
        final int total = _binding.editable;
        int loaded = total - left;
        _progress = (loaded: loaded, total: total);
        _notify();
        // The count moves on [progressChanges] alone: only the save bar
        // redraws for it, not the editor with every library row.
        void arrived(int count) {
          if (count == 0 || _disposed) return;
          loaded = math.min(loaded + count, total);
          _progress = (loaded: loaded, total: total);
          progressChanges.notify();
        }

        final List<SyncDocumentBody> waiting = <SyncDocumentBody>[];
        DateTime landedAt = _clock();
        Future<void> land() async {
          if (waiting.isEmpty) return;
          final List<SyncDocumentBody> batch = List<SyncDocumentBody>.of(
            waiting,
          );
          waiting.clear();
          await _land(batch);
          landedAt = _clock();
        }

        if (left > _readNowLimit * 5) {
          final EditorApi api = _api;
          int bytes = 0;
          for (final SyncBaseDocument row in _binding.documents.values) {
            if (row.editable && !row.loaded) bytes += row.size;
          }
          final int firstPageBytes = math.max(
            _fillMinPageBytes,
            math.min(api.pageBytes, bytes ~/ _fillFirstShare),
          );
          String? after;
          do {
            final (List<SyncDocumentBody> page, String? next) = await api
                .documentPage(
                  after: after,
                  maxBytes: after == null ? firstPageBytes : null,
                );
            if (session != _session || _disposed) return;
            int fresh = 0;
            for (final SyncDocumentBody body in page) {
              final SyncBaseDocument? base = _binding.documents[body.key];
              if (base != null && !base.loaded) {
                waiting.add(body);
                fresh++;
              }
            }
            arrived(fresh);
            after = next;
            if (_clock().difference(landedAt) >= landEvery) await land();
          } while (after != null);
        } else {
          final ({
            List<SyncDocumentBody> bodies,
            List<String> missing,
            List<String> failed,
          })
          read = await _api.readDocuments(
            unloaded,
            onBatch: (List<SyncDocumentBody> bodies) {
              waiting.addAll(bodies);
              arrived(bodies.length);
            },
          );
          if (session != _session || _disposed) return;
          await _unread(read.missing, read.failed);
        }
        await land();
      } while (_fillAgain);
    } on EditorSyncFailure catch (error) {
      _failed(error);
    } on SyncMalformed {
      _problem = () => transportCopy('dropped').text;
    } finally {
      _filling = false;
      _progress = null;
      _notify();
      // A fill asked for while this one ran (a new session, a new listing)
      // runs now, even when this one stopped early.
      if (_fillAgain && !_disposed) {
        _fillAgain = false;
        unawaited(_fill());
      }
    }
  }

  /// Settles listed documents a read did not return: [missing] ones are
  /// gone from the server, [failed] ones (refused by folder or size) are not
  /// asked for again until the next catalog read.
  Future<void> _unread(List<String> missing, List<String> failed) async {
    _unreadable.addAll(failed);
    if (missing.isEmpty) return;
    await _locked(
      () => _applyCatalog(SyncCatalog(deleted: missing), full: false),
    );
  }

  /// Reads [keys] once more after their first body arrived stale.
  Future<void> _readAgain(List<String> keys) async {
    if (!connectionStatus.online) return;
    try {
      final ({
        List<SyncDocumentBody> bodies,
        List<String> missing,
        List<String> failed,
      })
      read = await _api.readDocuments(keys);
      await _land(read.bodies, stale: true);
      await _unread(read.missing, read.failed);
    } on EditorSyncFailure catch (error) {
      _failed(error);
    }
    _notify();
  }

  /// Reads [keys] now (a document opened before the fill reached it).
  Future<void> _readNow(List<String> keys) async {
    if (!connectionStatus.online) return;
    try {
      final ({
        List<SyncDocumentBody> bodies,
        List<String> missing,
        List<String> failed,
      })
      read = await _api.readDocuments(keys);
      await _land(read.bodies);
      await _unread(read.missing, read.failed);
    } on EditorSyncFailure catch (error) {
      _failed(error);
    }
    _notify();
  }

  /// Writes filled bodies into the workspace. A document whose body is
  /// already here is skipped. A body older than the listing (the server
  /// moved it while it was read) is read again once; with [stale] it lands
  /// anyway and the next event settles it.
  Future<void> _land(List<SyncDocumentBody> bodies, {bool stale = false}) =>
      _locked(() async {
    final List<SyncDocWrite> writes = <SyncDocWrite>[];
    final List<SyncDocumentBody> taken = <SyncDocumentBody>[];
    final List<String> again = <String>[];
    for (final SyncDocumentBody body in bodies) {
      final SyncBaseDocument? base = _binding.documents[body.key];
      if (base == null || base.loaded || base.docId == null) continue;
      if (!stale && body.hash != base.hash) {
        again.add(body.key);
        continue;
      }
      writes.add(
        SyncDocWrite(
          key: body.key,
          json: body.json,
          title: _workspace.byId(base.docId)?.title ?? base.name,
          docId: base.docId,
        ),
      );
      taken.add(body);
    }
    if (again.isNotEmpty) unawaited(_readAgain(again));
    if (writes.isEmpty) return;
    store.flushAutosave(notify: false);
    final Map<String, String> landed = await _applyWrites(
      writes,
      const <String>{},
    );
    for (final SyncDocumentBody body in taken) {
      final String? docId = landed[body.key];
      final SyncBaseDocument base = _binding.documents[body.key]!;
      _binding.documents[body.key] = base.copyWith(
        hash: body.hash,
        revision: body.revision,
        loaded: true,
      );
      final WorkspaceDoc? doc = docId == null ? null : _workspace.byId(docId);
      if (doc != null) _memo.seed(doc, body.key, body.hash);
    }
    _touchBinding();
    _persist();
  });

  /// Fetches the images this browser lacks, 64 per request, and stores them
  /// in batches so the library writes a handful of times.
  Future<void> _fetchImages() async {
    if (_fetchingImages) {
      _fetchImagesAgain = true;
      return;
    }
    _fetchingImages = true;
    final int session = _session;
    try {
      do {
        _fetchImagesAgain = false;
        final Map<String, StoredImage> local = <String, StoredImage>{
          for (final StoredImage image in List<StoredImage>.of(images.images))
            image.path: image,
        };
        final Map<String, List<SyncBaseImage>> todo =
            <String, List<SyncBaseImage>>{};
        for (final SyncBaseImage base in List<SyncBaseImage>.of(
          _binding.images.values,
        )) {
          if (base.local) continue;
          final StoredImage? existing = local[base.path];
          if (existing != null && _memo.imageHash(existing) == base.hash) {
            _binding.images[base.path] = base.copyWith(local: true);
            continue;
          }
          todo.putIfAbsent(base.hash, () => <SyncBaseImage>[]).add(base);
        }
        if (todo.isEmpty) break;
        await _api.readImages(
          todo.keys,
          onBatch: (List<SyncImageData> batch) {
            if (session != _session || _disposed) return;
            final List<StoredImage> ready = <StoredImage>[];
            for (final SyncImageData data in batch) {
              for (final SyncBaseImage base
                  in todo[data.hash] ?? const <SyncBaseImage>[]) {
                final StoredImage? image = _storedImage(base, data);
                if (image != null) ready.add(image);
              }
            }
            if (ready.isNotEmpty && images.upsertAll(ready)) {
              for (final StoredImage image in ready) {
                final SyncBaseImage? base = _binding.images[image.path];
                if (base != null) {
                  _binding.images[image.path] = base.copyWith(local: true);
                }
              }
            }
          },
        );
        _touchBinding();
        _persist();
        _notify();
      } while (_fetchImagesAgain && session == _session);
    } on EditorSyncFailure catch (error) {
      _failed(error);
    } finally {
      _fetchingImages = false;
      if (_fetchImagesAgain && !_disposed) {
        _fetchImagesAgain = false;
        unawaited(_fetchImages());
      }
    }
  }

  StoredImage? _storedImage(SyncBaseImage base, SyncImageData data) {
    final StoredImage image = StoredImage(
      path: base.path,
      dataUri: 'data:${data.mediaType};base64,${base64Encode(data.bytes)}',
      width: base.width,
      height: base.height,
    );
    if (isValidStoredImageData(image)) return image;
    final StoredImageData? decoded = decodeSupportedImageData(image.dataUri);
    if (decoded == null) return null;
    return image.copyWith(width: decoded.width, height: decoded.height);
  }
}
