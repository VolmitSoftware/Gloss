import 'dart:convert';
import 'dart:typed_data';

import 'package:gloss_editor/services/editor_sync.dart' show editorSyncBytesHash, editorSyncCanonicalJson;

import 'kinds.dart';
import 'protocol.dart';
import 'store.dart';
import 'tokens.dart';

/// What a publish did: its answer, and what it wrote for the events.
class Publication {
  const Publication(this.answer, {this.documents = const <StoredDocument>[], this.deleted = const <(String, String)>[], this.images = const <StoredImage>[], this.removedImages = const <String>[]});

  final Map<String, Object?> answer;
  final List<StoredDocument> documents;
  final List<(String, String)> deleted;
  final List<StoredImage> images;
  final List<String> removedImages;

  bool get wrote => documents.isNotEmpty || deleted.isNotEmpty || images.isNotEmpty || removedImages.isNotEmpty;
}

class _DocumentChange {
  const _DocumentChange(this.delete, this.kind, this.id, this.baseHash, this.json);

  final bool delete;
  final GlossKind kind;
  final String id;
  final String? baseHash;
  final String? json;
}

class _ImageChange {
  const _ImageChange(this.delete, this.path, this.baseHash, this.hash, this.mediaType);

  final bool delete;
  final String path;
  final String? baseHash;
  final String? hash;
  final String? mediaType;
}

class _Staged {
  _Staged(this.bytes, this.info) : at = now();

  final Uint8List bytes;
  final ImageInfo info;
  DateTime at;
}

/// `POST /v1/images` staging and `POST /v1/changes`, with idempotency
/// answers kept in memory until a restart.
class FakeChanges {
  FakeChanges(this.store);

  final FakeStore store;
  final Map<String, Map<String, Object?>> _answers = <String, Map<String, Object?>>{};
  final Map<String, _Staged> _staged = <String, _Staged>{};

  int get stagedImages => _staged.length;

  /// A restart forgets idempotency answers and staged uploads.
  void restart() {
    _answers.clear();
    _staged.clear();
  }

  /// Drops uploads staged more than 10 minutes ago.
  void housekeeping() {
    final DateTime cutoff = now().subtract(const Duration(minutes: 10));
    _staged.removeWhere((String hash, _Staged staged) => staged.at.isBefore(cutoff));
  }

  /// `POST /v1/images`: 201 for new bytes, 200 for bytes already staged or stored.
  FakeResponse upload(FakeRequest request) {
    final Map<String, Object?> body = bodyObject(request);
    onlyFields(body, r'$', <String>{'mediaType', 'data'});
    final String mediaType = requireString(body, 'mediaType', r'$');
    if (!imageMediaTypes.contains(mediaType)) throw invalid('unsupported image type $mediaType');
    final Object? data = body['data'];
    if (data is! String || data.isEmpty) throw invalid(r'$.data: base64 of the image bytes');
    if (data.length > (imageBytes ~/ 3 + 1) * 4) throw ApiError('too-large', 'An image is at most $imageBytes bytes.');
    final Uint8List bytes;
    try {
      bytes = base64Decode(data);
    } on FormatException {
      throw invalid(r'$.data is not base64');
    }
    if (bytes.length > imageBytes) throw ApiError('too-large', 'An image is at most $imageBytes bytes.');
    final ImageInfo? info = imageInfo(bytes);
    if (info == null || info.mediaType != mediaType) throw invalid('bytes do not match $mediaType');
    if (info.width > imageSide || info.height > imageSide || info.width * info.height > imagePixels) {
      throw ApiError('too-large', 'An image is at most $imageSide px a side and $imagePixels px.');
    }
    final String hash = editorSyncBytesHash(bytes);
    final bool known = _staged.containsKey(hash) || store.imageWithHash(hash, (String path) => true) != null;
    _staged.remove(hash);
    _staged[hash] = _Staged(bytes, info);
    int total = _staged.values.fold(0, (int sum, _Staged staged) => sum + staged.bytes.length);
    while (total > stagedBytes) {
      final String oldest = _staged.keys.first;
      total -= _staged.remove(oldest)!.bytes.length;
    }
    return FakeResponse(known ? 200 : 201, body: <String, Object?>{
      'hash': hash,
      'size': bytes.length,
      'mediaType': info.mediaType,
      'width': info.width,
      'height': info.height,
    });
  }

  /// `POST /v1/changes`. Throws [ApiError] for a request refused whole.
  Publication publish(FakeRequest request, FakeToken token) {
    final Map<String, Object?> body = bodyObject(request);
    onlyFields(body, r'$', <String>{'idempotencyKey', 'documents', 'images', 'confirmDeletes'});
    final String key = requireString(body, 'idempotencyKey', r'$', pattern: idempotencyPattern);
    // A resend of an applied publish gets its first answer, whatever changed since.
    final Map<String, Object?>? earlier = _answers[key];
    if (earlier != null) return Publication(earlier);
    final List<_DocumentChange> documents = _documents(body['documents']);
    final List<_ImageChange> images = _images(body['images']);
    final Object? confirmed = body['confirmDeletes'];
    if (confirmed != null && (confirmed is! int || confirmed < 0)) throw invalid('confirmDeletes is the number of deletions.');
    if (documents.isEmpty && images.isEmpty) throw invalid('A publish names at least one document or image.');
    _checkScopes(token, documents, images);
    _confirmDeletes(token, documents, images, confirmed as int?);
    for (final _DocumentChange change in documents) {
      token.reach('${change.kind.wire} ${change.id}', change.id);
    }
    for (final _ImageChange change in images) {
      token.reach('image ${change.path}', change.path);
    }
    final Map<String, (Uint8List, ImageInfo)> bytes = _imageBytes(images);
    final Publication outcome = _apply(key, documents, images, bytes);
    _answers[key] = outcome.answer;
    if (outcome.answer['status'] == 'applied') {
      for (final StoredImage image in outcome.images) {
        _staged.remove(image.hash);
      }
    }
    return outcome;
  }

  /// Refuses a publish that deletes more than [confirmDeletesAbove] documents
  /// and images and more than [confirmDeletesPercent] percent of those the
  /// token reaches, unless `confirmDeletes` names that count.
  void _confirmDeletes(FakeToken token, List<_DocumentChange> documents, List<_ImageChange> images, int? confirmed) {
    final int deletes = documents.where((_DocumentChange change) => change.delete).length + images.where((_ImageChange change) => change.delete).length;
    if (deletes <= confirmDeletesAbove || deletes == confirmed) return;
    final int total = store.documents.where((StoredDocument document) => token.sees(document.id)).length + store.images.where((StoredImage image) => token.sees(image.path)).length;
    if (deletes * 100 <= total * confirmDeletesPercent) return;
    throw ApiError(
      'confirm-deletes',
      'Publishing would delete $deletes of the $total documents and images ${token.folders.isEmpty ? 'on this server' : 'this pairing reaches'}. Nothing was published.',
      <String, Object?>{'deletes': deletes, 'total': total},
    );
  }

  static String? _baseHash(Map<String, Object?> entry, String at) {
    final Object? hash = entry['baseHash'];
    if (hash == null) return null;
    if (hash is! String || !hashPattern.hasMatch(hash)) throw invalid('$at.baseHash: null or a sha256: hash');
    return hash;
  }

  static String _op(Map<String, Object?> entry, String at) {
    final Object? op = entry['op'];
    if (op != 'put' && op != 'delete') throw invalid('$at.op: put or delete');
    return op! as String;
  }

  static List<Map<String, Object?>> _list(Object? value, String name, int maximum) {
    if (value == null) return const <Map<String, Object?>>[];
    if (value is! List<Object?> || value.length > maximum) throw invalid('$name: at most $maximum entries');
    return <Map<String, Object?>>[
      for (int index = 0; index < value.length; index++)
        value[index] is Map<String, Object?> ? value[index]! as Map<String, Object?> : throw invalid('\$.$name[$index]: expected an object'),
    ];
  }

  List<_DocumentChange> _documents(Object? value) {
    final List<_DocumentChange> changes = <_DocumentChange>[];
    final Set<String> seen = <String>{};
    final List<Map<String, Object?>> entries = _list(value, 'documents', publishDocuments);
    for (int index = 0; index < entries.length; index++) {
      final String at = '\$.documents[$index]';
      final Map<String, Object?> entry = entries[index];
      final bool delete = _op(entry, at) == 'delete';
      onlyFields(entry, at, delete ? <String>{'op', 'kind', 'id', 'baseHash'} : <String>{'op', 'kind', 'id', 'baseHash', 'json'});
      final String kindName = requireString(entry, 'kind', at);
      final GlossKind kind = kindNamed(kindName) ?? (throw invalid('$at.kind: unknown kind $kindName'));
      final String id = requireString(entry, 'id', at);
      final String? problem = kind.idProblem(id);
      if (problem != null) throw invalid('$at.id: $problem');
      if (!seen.add(documentKey(kindName, id))) throw invalid('$at: $kindName $id is named twice');
      final String? baseHash = _baseHash(entry, at);
      if (delete && baseHash == null) throw invalid('$at.baseHash: a delete names the hash it removes');
      final Object? json = delete ? null : entry['json'];
      if (!delete && json is! String) throw invalid('$at.json: the body text');
      if (json is String && utf8.encode(json).length > documentBytes) throw ApiError('too-large', '$kindName $id is over $documentBytes bytes.');
      changes.add(_DocumentChange(delete, kind, id, baseHash, json as String?));
    }
    return changes;
  }

  List<_ImageChange> _images(Object? value) {
    final List<_ImageChange> changes = <_ImageChange>[];
    final Set<String> seen = <String>{};
    final List<Map<String, Object?>> entries = _list(value, 'images', publishImages);
    for (int index = 0; index < entries.length; index++) {
      final String at = '\$.images[$index]';
      final Map<String, Object?> entry = entries[index];
      final bool delete = _op(entry, at) == 'delete';
      onlyFields(entry, at, delete ? <String>{'op', 'path', 'baseHash'} : <String>{'op', 'path', 'baseHash', 'hash', 'mediaType'});
      final String path = requireString(entry, 'path', at, maxLength: 256);
      final String? problem = imagePathProblem(path);
      if (problem != null) throw invalid('$at.path: $problem');
      if (!seen.add(path)) throw invalid('$at: image $path is named twice');
      final String? baseHash = _baseHash(entry, at);
      if (delete && baseHash == null) throw invalid('$at.baseHash: a delete names the hash it removes');
      changes.add(_ImageChange(
        delete,
        path,
        baseHash,
        delete ? null : requireString(entry, 'hash', at, pattern: hashPattern),
        delete ? null : requireString(entry, 'mediaType', at),
      ));
    }
    return changes;
  }

  /// Contract 12: each document needs its kind's scope, each image
  /// publish:images, and a put whose body runs a console command
  /// publish:commands. The first scope missing refuses the whole call.
  static void _checkScopes(FakeToken token, List<_DocumentChange> documents, List<_ImageChange> images) {
    final Map<String, List<String>> lacking = <String, List<String>>{};
    for (final _DocumentChange change in documents) {
      if (!token.has(change.kind.scope)) lacking.putIfAbsent(change.kind.scope, () => <String>[]).add('${change.kind.wire} ${change.id}');
      if (change.json != null && !token.has('publish:commands')) {
        try {
          if (runsCommands(jsonDecode(change.json!))) lacking.putIfAbsent('publish:commands', () => <String>[]).add('${change.kind.wire} ${change.id}');
        } on FormatException {
          // Rejected later as unreadable.
        }
      }
    }
    if (images.isNotEmpty && !token.has('publish:images')) lacking['publish:images'] = <String>[for (final _ImageChange change in images) 'image ${change.path}'];
    for (final String scope in allScopes) {
      final List<String>? named = lacking[scope];
      if (named == null) continue;
      final String what = scope == 'publish:commands' ? ' to publish documents that run console commands' : '';
      throw ApiError('scope-missing', 'This token lacks $scope$what: ${named.join(', ')}.', <String, Object?>{'scope': scope});
    }
  }

  /// The bytes of every image put, staged or already stored; refuses the
  /// call naming hashes the server does not hold.
  Map<String, (Uint8List, ImageInfo)> _imageBytes(List<_ImageChange> images) {
    final Map<String, (Uint8List, ImageInfo)> found = <String, (Uint8List, ImageInfo)>{};
    final List<String> missing = <String>[];
    for (final _ImageChange change in images) {
      if (change.delete) continue;
      final _Staged? staged = _staged[change.hash];
      final StoredImage? stored = staged == null ? store.imageWithHash(change.hash!, (String path) => true) : null;
      if (staged == null && stored == null) {
        missing.add(change.hash!);
        continue;
      }
      final (Uint8List, ImageInfo) value = staged != null ? (staged.bytes, staged.info) : (stored!.bytes, stored.info);
      if (value.$2.mediaType != change.mediaType) throw invalid('image ${change.path}: bytes do not match ${change.mediaType}');
      found[change.hash!] = value;
    }
    if (missing.isNotEmpty) throw invalid('Upload these images with POST /v1/images first.', <String, Object?>{'missing': missing});
    return found;
  }

  Publication _apply(String key, List<_DocumentChange> documents, List<_ImageChange> images, Map<String, (Uint8List, ImageInfo)> bytes) {
    final String changeId = 'chg_${randomText(12)}';
    Map<String, Object?> answer(String status, String message, List<Map<String, Object?>> conflicts, Publication delta) => <String, Object?>{
          'changeId': changeId,
          'idempotencyKey': key,
          'status': status,
          'message': message,
          'conflicts': conflicts,
          'documents': <Object?>[for (final StoredDocument document in delta.documents) document.entry],
          'deleted': <Object?>[for (final (String kind, String id) in delta.deleted) <String, Object?>{'kind': kind, 'id': id}],
          'images': <Object?>[for (final StoredImage image in delta.images) image.entry],
          'removedImages': <Object?>[for (final String path in delta.removedImages) <String, Object?>{'path': path}],
        };

    // Every body must be readable before anything is judged.
    final Map<String, StoredDocument> bodies = <String, StoredDocument>{};
    for (final _DocumentChange change in documents) {
      if (change.delete) continue;
      String? problem;
      final StoredDocument? parsed = StoredDocument.of(change.kind, change.id, change.json!, (String why) => problem = why);
      if (parsed != null && change.baseHash == null && change.kind.versioned && parsed.revision != 1) problem = 'a new document starts at revision 1';
      if (problem != null) {
        return Publication(answer('rejected', '${change.kind.wire} ${change.id}: $problem', const <Map<String, Object?>>[], const Publication(<String, Object?>{})));
      }
      bodies[documentKey(change.kind.wire, change.id)] = parsed!;
    }

    final List<Map<String, Object?>> conflicts = <Map<String, Object?>>[];
    final List<void Function()> writes = <void Function()>[];
    final List<StoredDocument> landedDocuments = <StoredDocument>[];
    final List<(String, String)> landedDeletes = <(String, String)>[];
    final List<StoredImage> landedImages = <StoredImage>[];
    final List<String> landedRemoves = <String>[];
    final List<StoredDocument> written = <StoredDocument>[];
    final List<(String, String)> deleted = <(String, String)>[];
    final List<StoredImage> writtenImages = <StoredImage>[];
    final List<String> removedImages = <String>[];

    for (final _DocumentChange change in documents) {
      final StoredDocument? existing = store.document(change.kind, change.id);
      final StoredDocument? body = bodies[documentKey(change.kind.wire, change.id)];
      void conflict(String reason) => conflicts.add(<String, Object?>{
            'kind': change.kind.wire,
            'id': change.id,
            'name': summaryName(change.id, existing?.parsed ?? body?.parsed ?? const <String, Object?>{}),
            'reason': reason,
          });
      if (change.delete) {
        if (existing == null) {
          landedDeletes.add((change.kind.wire, change.id));
        } else if (existing.hash != change.baseHash) {
          conflict('changed');
        } else {
          landedDeletes.add((change.kind.wire, change.id));
          writes.add(() {
            store.deleteDocument(change.kind, change.id, 'editor');
            deleted.add((change.kind.wire, change.id));
          });
        }
        continue;
      }
      if (existing != null && _sameContent(existing, body!)) {
        landedDocuments.add(existing);
      } else if (existing == null && change.baseHash != null) {
        conflict('deleted');
      } else if (existing != null && change.baseHash == null) {
        conflict('exists');
      } else if (existing != null && (existing.hash != change.baseHash || existing.revision != body!.revision)) {
        conflict('changed');
      } else {
        final StoredDocument next = existing != null && change.kind.versioned ? _bumped(body!, existing.revision! + 1) : body!;
        landedDocuments.add(next);
        writes.add(() => written.add(store.putDocument(next, 'editor')));
      }
    }

    for (final _ImageChange change in images) {
      final StoredImage? existing = store.image(change.path);
      void conflict(String reason) => conflicts.add(<String, Object?>{'kind': 'image', 'id': change.path, 'name': change.path, 'reason': reason});
      if (change.delete) {
        if (existing == null) {
          landedRemoves.add(change.path);
        } else if (existing.hash != change.baseHash) {
          conflict('changed');
        } else {
          landedRemoves.add(change.path);
          writes.add(() {
            store.deleteImage(change.path);
            removedImages.add(change.path);
          });
        }
      } else if (existing != null && existing.hash == change.hash) {
        landedImages.add(existing);
      } else if (existing == null && change.baseHash != null) {
        conflict('deleted');
      } else if (existing != null && change.baseHash == null) {
        conflict('exists');
      } else if (existing != null && existing.hash != change.baseHash) {
        conflict('changed');
      } else {
        final (Uint8List data, ImageInfo info) = bytes[change.hash]!;
        final StoredImage image = StoredImage(change.path, data, info);
        landedImages.add(image);
        writes.add(() => writtenImages.add(store.putImage(image)));
      }
    }

    final int landed = landedDocuments.length + landedDeletes.length + landedImages.length + landedRemoves.length;
    final String kept = conflicts.isEmpty ? '' : '${conflicts.length} kept the server copy: ${conflicts.map((Map<String, Object?> entry) => entry['name']).join(', ')}';
    if (landed == 0) {
      return Publication(answer('conflict', 'Nothing published; $kept.', conflicts, const Publication(<String, Object?>{})));
    }
    for (final void Function() write in writes) {
      write();
    }
    final String message = 'Published $landed change${landed == 1 ? '' : 's'}${kept.isEmpty ? '' : '; $kept'}.';
    final Publication delta = Publication(const <String, Object?>{}, documents: landedDocuments, deleted: landedDeletes, images: landedImages, removedImages: landedRemoves);
    return Publication(answer('applied', message, conflicts, delta), documents: written, deleted: deleted, images: writtenImages, removedImages: removedImages);
  }

  /// A put equal to the server copy counts as landed. For a versioned kind
  /// the body may name the revision before the server's bump, so a retry of
  /// an applied publish is equal too.
  static bool _sameContent(StoredDocument existing, StoredDocument body) {
    if (existing.hash == body.hash) return true;
    if (!existing.kind.versioned || body.revision! + 1 != existing.revision) return false;
    return editorSyncCanonicalJson(<String, Object?>{...body.parsed, 'revision': existing.revision}) == editorSyncCanonicalJson(existing.parsed);
  }

  /// [body] with its revision set to [revision], written as pretty JSON.
  static StoredDocument _bumped(StoredDocument body, int revision) {
    final Map<String, Object?> json = <String, Object?>{...body.parsed, 'revision': revision};
    return StoredDocument.of(body.kind, body.id, '${const JsonEncoder.withIndent('  ').convert(json)}\n')!;
  }
}
