import 'dart:convert';
import 'dart:math';

import 'kinds.dart';
import 'protocol.dart';
import 'store.dart';
import 'tokens.dart';

/// A position in catalog order: section 0 is documents by `kind NUL id`,
/// section 1 images by path.
typedef Cursor = (int, String);

String encodeCursor(Cursor cursor) => base64UrlNoPadding(utf8.encode('${cursor.$1}\n${cursor.$2}'));

Cursor? decodeCursor(String? value) {
  if (value == null || value.isEmpty) return null;
  try {
    final String text = utf8.decode(base64Url.decode(base64Url.normalize(value)));
    final int newline = text.indexOf('\n');
    final int? section = newline < 0 ? null : int.tryParse(text.substring(0, newline));
    if (section == 0 || section == 1) return (section!, text.substring(newline + 1));
  } on FormatException {
    // Refused below.
  }
  throw invalid('after: not a cursor this server gave');
}

bool _after(Cursor? cursor, int section, String key) =>
    cursor == null || section > cursor.$1 || (section == cursor.$1 && key.compareTo(cursor.$2) > 0);

void _onlyQuery(FakeRequest request, Set<String> allowed) {
  for (final String key in request.query.keys) {
    if (!allowed.contains(key)) throw invalid('Unknown query parameter $key.');
  }
}

/// The read endpoints of contract section 10.
class FakeReads {
  FakeReads(this.store, this.pageEntries);

  final FakeStore store;

  /// The most entries one catalog page holds (1000).
  final int pageEntries;

  /// `GET /v1/catalog?after=`: documents, then images, the token can see.
  FakeResponse catalog(FakeRequest request, FakeToken token, String metaHash, String notesHash) {
    _onlyQuery(request, <String>{'after'});
    final Cursor? cursor = decodeCursor(request.query['after']);
    final List<Map<String, Object?>> documents = <Map<String, Object?>>[];
    final List<Map<String, Object?>> images = <Map<String, Object?>>[];
    int bytes = 0;
    Cursor? last;
    String? next;
    bool take(int section, String key, Map<String, Object?> entry, int size, List<Map<String, Object?>> into) {
      if (documents.length + images.length >= pageEntries || (bytes > 0 && bytes + size > catalogPageBytes)) {
        next = encodeCursor(last!);
        return false;
      }
      into.add(entry);
      bytes += size + 1;
      last = (section, key);
      return true;
    }

    for (final StoredDocument document in store.documents) {
      if (!_after(cursor, 0, document.key) || !token.sees(document.id)) continue;
      if (!take(0, document.key, document.entry, document.entryBytes, documents)) break;
    }
    if (next == null) {
      for (final StoredImage image in store.images) {
        if (!_after(cursor, 1, image.path) || !token.sees(image.path)) continue;
        if (!take(1, image.path, image.entry, image.entryBytes, images)) break;
      }
    }
    return FakeResponse(200, body: <String, Object?>{
      'documents': documents,
      'images': images,
      'metaHash': metaHash,
      'notesHash': notesHash,
      'next': next,
    });
  }

  /// `GET /v1/documents?after=&maxBytes=`: bodies in catalog order.
  FakeResponse page(FakeRequest request, FakeToken token) {
    _onlyQuery(request, <String>{'after', 'maxBytes'});
    final Cursor? cursor = decodeCursor(request.query['after']);
    final String? asked = request.query['maxBytes'];
    final int? maxBytes = asked == null ? defaultPageBytes : int.tryParse(asked);
    if (maxBytes == null || maxBytes < 1 || maxBytes > maxPageBytes) throw invalid('maxBytes: 1 to $maxPageBytes');
    final bool relay = request.transport == Transport.relay;
    final int budget = relay ? min(maxBytes, relayRoom) : maxBytes;
    final List<Map<String, Object?>> documents = <Map<String, Object?>>[];
    int bytes = 0;
    String? last;
    String? next;
    for (final StoredDocument document in store.documents) {
      if (!_after(cursor, 0, document.key) || !token.sees(document.id)) continue;
      if (documents.isNotEmpty && bytes + document.bodyBytes > budget) {
        next = encodeCursor((0, last!));
        break;
      }
      if (relay && document.bodyBytes > relayRoom) {
        throw ApiError('too-large', '${document.kind.wire} ${document.id} is larger than a relay frame; read it on the direct path.');
      }
      documents.add(document.body);
      bytes += document.bodyBytes + 1;
      last = document.key;
    }
    return FakeResponse(200, body: <String, Object?>{'documents': documents, 'next': next});
  }

  /// `POST /v1/documents/read`: documents in request order until the answer
  /// is full, per-ref errors, and the refs left for the next call.
  FakeResponse batch(FakeRequest request, FakeToken token) {
    final Map<String, Object?> body = bodyObject(request);
    onlyFields(body, r'$', <String>{'refs'});
    final Object? refs = body['refs'];
    if (refs is! List<Object?> || refs.isEmpty || refs.length > batchRefs) throw invalid('refs: 1 to $batchRefs {kind, id} references');
    final List<(String, String)> wanted = <(String, String)>[];
    final Set<String> seen = <String>{};
    for (int index = 0; index < refs.length; index++) {
      final String at = '\$.refs[$index]';
      final Object? ref = refs[index];
      if (ref is! Map<String, Object?>) throw invalid('$at: expected {kind, id}');
      onlyFields(ref, at, <String>{'kind', 'id'});
      final String kind = requireString(ref, 'kind', at), id = requireString(ref, 'id', at);
      if (!seen.add(documentKey(kind, id))) throw invalid('$at: $kind $id is named twice');
      wanted.add((kind, id));
    }
    final bool relay = request.transport == Transport.relay;
    final int budget = relay ? relayRoom : batchAnswerBytes;
    final List<Map<String, Object?>> documents = <Map<String, Object?>>[];
    final List<Map<String, Object?>> errors = <Map<String, Object?>>[];
    final List<Map<String, Object?>> remaining = <Map<String, Object?>>[];
    int bytes = 0;
    for (final (String kindName, String id) in wanted) {
      Map<String, Object?> refused(String code, String message) => <String, Object?>{'kind': kindName, 'id': id, 'code': code, 'message': message};
      if (remaining.isNotEmpty) {
        remaining.add(<String, Object?>{'kind': kindName, 'id': id});
        continue;
      }
      final GlossKind? kind = kindNamed(kindName);
      final String? problem = kind == null ? 'Unknown kind $kindName.' : kind.idProblem(id);
      if (problem != null) {
        errors.add(refused('invalid-request', problem));
        continue;
      }
      if (!token.sees(id)) {
        errors.add(refused('folder-denied', 'This token does not reach $kindName $id.'));
        continue;
      }
      final StoredDocument? document = store.document(kind!, id);
      if (document == null) {
        errors.add(refused('not-found', 'No $kindName $id.'));
        continue;
      }
      if (relay && document.readBytes > relayRoom) {
        errors.add(refused('too-large', 'Larger than a relay frame; read it on the direct path.'));
        continue;
      }
      if (documents.isNotEmpty && bytes + document.readBytes > budget) {
        remaining.add(<String, Object?>{'kind': kindName, 'id': id});
        continue;
      }
      documents.add(document.read);
      bytes += document.readBytes + 1;
    }
    return FakeResponse(200, body: <String, Object?>{'documents': documents, 'errors': errors, 'remaining': remaining});
  }

  /// `GET /v1/documents/{kind}/{id}`; [segments] start after `documents`.
  FakeResponse document(List<String> segments, FakeToken token) {
    final GlossKind kind = kindNamed(segments.first) ?? (throw ApiError('not-found', 'No kind ${segments.first}.'));
    final String id = segments.skip(1).map(decodeSegment).join('/');
    final String? problem = kind.idProblem(id);
    if (problem != null) throw invalid(problem);
    token.reach('${kind.wire} $id', id);
    final StoredDocument document = store.document(kind, id) ?? (throw ApiError('not-found', 'No ${kind.wire} $id.'));
    return FakeResponse(200, body: document.read);
  }

  /// `POST /v1/images/read`: image bytes by hash, as base64.
  FakeResponse images(FakeRequest request, FakeToken token) {
    final Map<String, Object?> body = bodyObject(request);
    onlyFields(body, r'$', <String>{'hashes'});
    final Object? hashes = body['hashes'];
    if (hashes is! List<Object?> || hashes.isEmpty || hashes.length > imageHashesPerRead) {
      throw invalid('hashes: 1 to $imageHashesPerRead sha256: hashes');
    }
    final Set<String> seen = <String>{};
    for (final Object? hash in hashes) {
      if (hash is! String || !hashPattern.hasMatch(hash)) throw invalid('hashes: $hash is not a sha256: hash');
      if (!seen.add(hash)) throw invalid('hashes: $hash is named twice');
    }
    final int budget = request.transport == Transport.relay ? relayRoom : batchAnswerBytes;
    final List<Map<String, Object?>> images = <Map<String, Object?>>[];
    final List<String> missing = <String>[];
    final List<String> remaining = <String>[];
    int bytes = 0;
    for (final String hash in seen) {
      if (remaining.isNotEmpty) {
        remaining.add(hash);
        continue;
      }
      final StoredImage? image = store.imageWithHash(hash, token.sees);
      if (image == null) {
        missing.add(hash);
        continue;
      }
      final int size = image.base64.length + 128;
      if (images.isNotEmpty && bytes + size > budget) {
        remaining.add(hash);
        continue;
      }
      images.add(<String, Object?>{'hash': hash, 'mediaType': image.info.mediaType, 'data': image.base64});
      bytes += size;
    }
    return FakeResponse(200, body: <String, Object?>{'images': images, 'missing': missing, 'remaining': remaining});
  }

  /// `GET /v1/images/<64 hex>` with an ETag; `If-None-Match` gives 304.
  FakeResponse image(FakeRequest request, FakeToken token, String hex) {
    final StoredImage? image = RegExp(r'^[0-9a-f]{64}$').hasMatch(hex) ? store.imageWithHash('sha256:$hex', token.sees) : null;
    if (image == null) throw ApiError('not-found', 'No image with hash sha256:$hex.');
    final Map<String, String> headers = <String, String>{'ETag': '"$hex"', 'Cache-Control': 'private, max-age=31536000, immutable'};
    if (matchesETag(request, hex)) return FakeResponse(304, headers: headers);
    return FakeResponse(200, headers: headers, body: <String, Object?>{'hash': image.hash, 'mediaType': image.info.mediaType, 'data': image.base64});
  }

  /// `GET /v1/history?kind=<collection>&id=&version=`.
  FakeResponse history(FakeRequest request, FakeToken token) {
    _onlyQuery(request, <String>{'kind', 'id', 'version'});
    final Map<String, String> query = request.query;
    final String collection = query['kind'] ?? '';
    final String id = query['id'] ?? '';
    final int? version = int.tryParse(query['version'] ?? '');
    final GlossKind? kind = kindOfCollection(collection);
    if (kind == null) throw invalid('kind: a history collection such as holograms or motd');
    if (kind.idProblem(id) != null || version == null) throw invalid('id and version: a document id and a whole version number');
    token.reach('$collection $id', id);
    final HistoryRow? row = store.version(collection, id, version);
    return FakeResponse(200, body: <String, Object?>{'kind': collection, 'id': id, 'version': version, 'found': row != null, 'json': row?.json});
  }
}

/// Whether `If-None-Match` names [tag] (with or without quotes, or `*`).
bool matchesETag(FakeRequest request, String tag) {
  final String? header = request.header('if-none-match');
  if (header == null) return false;
  return header.split(',').map((String part) => part.trim().replaceFirst('W/', '').replaceAll('"', '')).any((String part) => part == tag || part == '*');
}
