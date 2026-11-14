import 'dart:convert';
import 'dart:typed_data';

import 'package:gloss_editor/services/editor_sync.dart' show editorSyncDocumentHash;

import 'changes.dart';
import 'events.dart';
import 'identity.dart';
import 'kinds.dart';
import 'protocol.dart';
import 'reads.dart';
import 'store.dart';
import 'tokens.dart';

/// The answer to opening an event stream: refused with an error response, or
/// open with its first frame and a handle that removes the stream.
class EventsOpened {
  const EventsOpened(this.response, [this.open, this.remove]);

  final FakeResponse response;
  final Map<String, Object?>? open;
  final void Function()? remove;
}

typedef _Handler = FakeResponse Function(FakeRequest request, FakeToken token, List<String> segments);

/// The transport-neutral `/v1` API: origins, tokens, scopes, counters and
/// limits are checked here for both paths.
class FakeApi {
  FakeApi({
    required String? dataDirectory,
    required this.allowedOrigins,
    required this.serverName,
    required int catalogPageSize,
    required int requestsPerSecond,
    required int pairAttemptsPerMinute,
  }) : store = FakeStore(dataDirectory) {
    tokens = FakeTokens(requestsPerSecond, pairAttemptsPerMinute, (String id, String code) => events.closeToken(id, code));
    reads = FakeReads(store, catalogPageSize);
    changes = FakeChanges(store);
    store.load();
    metaHash = editorSyncDocumentHash(jsonEncode(_meta));
  }

  static const Map<String, String> _cors = <String, String>{
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Gloss-Counter, X-Gloss-Challenge, If-None-Match',
    'Access-Control-Expose-Headers': 'ETag, Retry-After',
    'Access-Control-Max-Age': '600',
    'Access-Control-Allow-Private-Network': 'true',
    'Vary': 'Origin',
  };

  final FakeStore store;
  final EventHub events = EventHub();
  late final FakeTokens tokens;
  late final FakeReads reads;
  late final FakeChanges changes;
  late final String metaHash;
  List<String> allowedOrigins;
  String serverName;

  /// `/__fake/latency`: every API request waits this long before it is
  /// handled.
  int latencyMillis = 0;

  /// `/__fake/latency` `answerMillis`: every API answer waits this long after
  /// the request was handled, so it describes the server as it was.
  int answerLatencyMillis = 0;

  /// `/__fake/older-answers`: a publish answer and its `editor` event carry
  /// every change since the last publish, not only the changeset's keys, as
  /// plugins before the changeset-only answer did.
  bool olderAnswers = false;

  /// Changes made with `quiet` and not announced yet, by key.
  final Map<String, StoredDocument?> _quiet = <String, StoredDocument?>{};

  /// Edits made with `afterRead` or `beforeRead`, run once a batch read that
  /// names their key is answered: after it (the answer holds the old copy)
  /// or before it.
  final Map<String, void Function()> _afterRead = <String, void Function()>{};
  final Map<String, void Function()> _beforeRead = <String, void Function()>{};

  /// Runs [edit] right after, or with [before] right before, the next batch
  /// read of [kind] [id].
  void editAroundRead(String kind, String id, void Function() edit, {bool before = false}) =>
      (before ? _beforeRead : _afterRead)[documentKey(kind, id)] = edit;

  /// Requests per `METHOD /v1/route`, ids folded into `{kind}`, `{id}`, `{hex}`.
  final Map<String, int> requestCounts = <String, int>{};
  Map<String, Object?>? lastPublishRequest;
  Map<String, Object?>? lastPublishAnswer;

  Map<String, Object?> get _meta => <String, Object?>{
        'format': 'gloss-editor-meta',
        'version': 1,
        'kinds': <Object?>[for (final GlossKind kind in glossKinds) kind.meta],
        'schemas': const <String, Object?>{},
        'defaults': const <String, Object?>{},
      };

  /// The notes [token] may see: warnings and history rows inside its folders.
  Map<String, Object?> notes(FakeToken token) {
    bool visible(String warning) {
      final List<String> parts = warning.split('|');
      return parts.length < 3 || token.sees(parts[2]);
    }

    return <String, Object?>{
      'format': 'gloss-editor-notes',
      'version': 1,
      'warnings': <String>[for (final String warning in store.warnings) if (visible(warning)) warning],
      'history': <Object?>[for (final HistoryRow row in store.history) if (token.sees(row.id)) row.row],
    };
  }

  String notesHash(FakeToken token) => editorSyncDocumentHash(jsonEncode(notes(token)));

  /// CORS headers for an allowed browser origin on the direct path.
  Map<String, String> cors(FakeRequest request) {
    final String? origin = request.header('origin');
    if (request.transport != Transport.direct || origin == null || !allowedOrigins.contains(origin)) return const <String, String>{};
    return <String, String>{..._cors, 'Access-Control-Allow-Origin': origin};
  }

  /// Refuses a direct request whose Origin is not allowed.
  void checkOrigin(FakeRequest request) {
    final String? origin = request.header('origin');
    if (request.transport == Transport.direct && origin != null && !allowedOrigins.contains(origin)) {
      throw ApiError('origin-not-allowed', 'Origin $origin is not in [editor] allowedOrigins.');
    }
  }

  static String routeName(String method, String route) {
    final List<String> s = route.split('/');
    if (s.length >= 4 && s[2] == 'documents' && s[3] != 'read') return '$method /v1/documents/{kind}/{id}';
    if (s.length == 4 && s[2] == 'images' && s[3] != 'read') return '$method /v1/images/{hex}';
    return '$method $route';
  }

  Future<FakeResponse> handle(FakeRequest request) async {
    final String name = routeName(request.method, request.route);
    requestCounts[name] = (requestCounts[name] ?? 0) + 1;
    if (latencyMillis > 0) await Future<void>.delayed(Duration(milliseconds: latencyMillis));
    if (_beforeRead.isNotEmpty && name == 'POST /v1/documents/read') _runAroundRead(request.body, _beforeRead);
    FakeResponse response;
    try {
      response = await _route(request);
    } on ApiError catch (refused) {
      response = refused.toResponse();
    }
    if (_afterRead.isNotEmpty && name == 'POST /v1/documents/read') _runAroundRead(request.body, _afterRead);
    if (answerLatencyMillis > 0) await Future<void>.delayed(Duration(milliseconds: answerLatencyMillis));
    final Map<String, String> headers = cors(request);
    return headers.isEmpty ? response : response.withHeaders(headers);
  }

  static void _runAroundRead(Object? body, Map<String, void Function()> edits) {
    final Object? refs = body is Map<String, Object?> ? body['refs'] : null;
    if (refs is! List<Object?>) return;
    for (final Object? ref in refs) {
      if (ref is! Map<String, Object?>) continue;
      final void Function()? edit = edits.remove(documentKey('${ref['kind']}', '${ref['id']}'));
      edit?.call();
    }
  }

  Future<FakeResponse> _route(FakeRequest request) async {
    checkOrigin(request);
    final String method = request.method;
    final String path = request.route;
    if (method == 'OPTIONS') return const FakeResponse(204);
    if (!path.startsWith('/v1/')) {
      if (RegExp(r'^/v[0-9]+(/|$)').hasMatch(path)) throw ApiError('unsupported-protocol', 'This server speaks protocol 1 at /v1/.');
      throw ApiError('not-found', 'No endpoint $path.');
    }
    if (method == 'GET' && path == '/v1/hello') return _hello(request);
    if (method == 'POST' && path == '/v1/pair') return _pair(request);
    if (method == 'DELETE' && path == '/v1/token') return _forget(request);
    final List<String> segments = path.substring(4).split('/');
    final (String?, bool, _Handler)? route = _match(method, segments);
    if (route == null) throw ApiError('not-found', 'No endpoint $method $path.');
    final FakeToken token = tokens.authenticate(_bearer(request));
    final (String? scope, bool counted, _Handler handler) = route;
    if (scope == 'publish:' && !token.scopes.any((String granted) => granted.startsWith('publish:'))) {
      throw ApiError('scope-missing', 'This token has no publish scope.', <String, Object?>{'scope': 'publish:'});
    }
    if (scope != null && scope != 'publish:' && !token.has(scope)) {
      throw ApiError('scope-missing', 'This token lacks $scope.', <String, Object?>{'scope': scope});
    }
    final void Function() release = tokens.admit(token);
    try {
      if (counted) tokens.advance(token, request.header('x-gloss-counter'));
      return handler(request, token, segments);
    } finally {
      release();
    }
  }

  /// `(scope, counted, handler)` of a route; `publish:` means any publish scope.
  (String?, bool, _Handler)? _match(String method, List<String> s) {
    final int n = s.length;
    return switch ((method, s.first)) {
      ('GET', 'token') when n == 1 => (null, false, (FakeRequest r, FakeToken t, List<String> _) => FakeResponse(200, body: _tokenInfo(t))),
      ('GET', 'catalog') when n == 1 => ('read', false, (FakeRequest r, FakeToken t, List<String> _) => reads.catalog(r, t, metaHash, notesHash(t))),
      ('GET', 'documents') when n == 1 => ('read', false, (FakeRequest r, FakeToken t, List<String> _) => reads.page(r, t)),
      ('GET', 'documents') when n >= 3 => ('read', false, (FakeRequest r, FakeToken t, List<String> s) => reads.document(s.sublist(1), t)),
      ('POST', 'documents') when n == 2 && s[1] == 'read' => ('read', false, (FakeRequest r, FakeToken t, List<String> _) => reads.batch(r, t)),
      ('POST', 'images') when n == 2 && s[1] == 'read' => ('read', false, (FakeRequest r, FakeToken t, List<String> _) => reads.images(r, t)),
      ('GET', 'images') when n == 2 => ('read', false, (FakeRequest r, FakeToken t, List<String> s) => reads.image(r, t, s[1])),
      ('POST', 'images') when n == 1 => ('publish:images', false, (FakeRequest r, FakeToken t, List<String> _) => changes.upload(r)),
      ('GET', 'meta') when n == 1 => ('read', false, (FakeRequest r, FakeToken t, List<String> _) => _tagged(r, metaHash, <String, Object?>{'metaHash': metaHash, 'meta': _meta})),
      ('GET', 'notes') when n == 1 => ('read', false, (FakeRequest r, FakeToken t, List<String> _) => _notes(r, t)),
      ('GET', 'history') when n == 1 => ('history', false, (FakeRequest r, FakeToken t, List<String> _) => reads.history(r, t)),
      ('POST', 'changes') when n == 1 => ('publish:', true, (FakeRequest r, FakeToken t, List<String> _) => _publish(r, t)),
      _ => null,
    };
  }

  static String _bearer(FakeRequest request) {
    final String? header = request.header('authorization');
    if (header == null || header.length < 7 || header.substring(0, 7).toLowerCase() != 'bearer ') {
      throw ApiError('unauthorized', 'Authorization: Bearer <token> is required.');
    }
    return header.substring(7).trim();
  }

  /// A JSON answer with `ETag: "<hash>"`; `If-None-Match` gives 304.
  static FakeResponse _tagged(FakeRequest request, String hash, Map<String, Object?> body) {
    final Map<String, String> headers = <String, String>{'ETag': '"$hash"'};
    return matchesETag(request, hash) ? FakeResponse(304, headers: headers) : FakeResponse(200, headers: headers, body: body);
  }

  FakeResponse _notes(FakeRequest request, FakeToken token) {
    final Map<String, Object?> notes = this.notes(token);
    final String hash = editorSyncDocumentHash(jsonEncode(notes));
    return _tagged(request, hash, <String, Object?>{'notesHash': hash, 'notes': notes});
  }

  Map<String, Object?> hello() => <String, Object?>{
        'protocol': 1,
        'protocols': <int>[1],
        'fingerprint': fakeFingerprint,
        'gloss': '3.3.0',
        'platform': 'paper',
        'minecraft': '26.3',
        'serverName': serverName,
      };

  Future<FakeResponse> _hello(FakeRequest request) async {
    final String? challenge = request.header(challengeHeader);
    if (challenge == null) return FakeResponse(200, body: hello());
    if (!secretPattern.hasMatch(challenge)) throw invalid('$challengeHeader: 32 random bytes as base64url, 43 characters');
    return FakeResponse(200, body: hello(), headers: await proofHeaders(challenge));
  }

  FakeResponse _pair(FakeRequest request) {
    tokens.pairAttempt(request.address);
    final Map<String, Object?> body = bodyObject(request);
    onlyFields(body, r'$', <String>{'tokenId', 'secret', 'client'});
    final String tokenId = requireString(body, 'tokenId', r'$', pattern: tokenIdPattern);
    final String secret = requireString(body, 'secret', r'$', pattern: secretPattern);
    final Object? client = body['client'];
    if (client != null && (client is! String || client.length > 128)) throw invalid(r'$.client: at most 128 characters');
    final (String text, FakeToken token) = tokens.exchange(tokenId, secret, client as String?);
    return FakeResponse(200, body: <String, Object?>{
      'token': text,
      'tokenId': token.id,
      'scopes': token.scopes,
      if (token.folders.isNotEmpty) 'folders': token.folders,
      'expiresAt': instant(token.expires),
      'lifetimeSeconds': token.lifetime.inSeconds,
      'fingerprint': fakeFingerprint,
      'counter': token.counter,
      'admin': ?token.admin,
      'serverName': serverName,
    });
  }

  /// `DELETE /v1/token` (Animus's Forget server): the bearer revokes itself.
  FakeResponse _forget(FakeRequest request) {
    try {
      tokens.revoke(tokens.authenticate(_bearer(request)).id);
    } on ApiError catch (refused) {
      if (refused.code != 'token-revoked') rethrow;
    }
    return const FakeResponse(204);
  }

  Map<String, Object?> _tokenInfo(FakeToken token) => <String, Object?>{
        'tokenId': token.id,
        'label': ?token.label,
        'scopes': token.scopes,
        if (token.folders.isNotEmpty) 'folders': token.folders,
        'expiresAt': instant(token.expires),
        'lifetimeSeconds': token.lifetime.inSeconds,
        'serverName': serverName,
        'fingerprint': fakeFingerprint,
      };

  FakeResponse _publish(FakeRequest request, FakeToken token) {
    lastPublishRequest = request.body is Map<String, Object?> ? request.body! as Map<String, Object?> : null;
    lastPublishAnswer = null;
    final Publication publication;
    try {
      publication = changes.publish(request, token);
    } on ApiError catch (refused) {
      lastPublishAnswer = refused.body;
      rethrow;
    }
    Map<String, Object?> answer = publication.answer;
    if (publication.wrote) {
      final List<StoredDocument> quietDocuments = <StoredDocument>[for (final StoredDocument? document in _quiet.values) ?document];
      final List<(String, String)> quietDeletes = <(String, String)>[
        for (final MapEntry<String, StoredDocument?> entry in _quiet.entries)
          if (entry.value == null) (entry.key.split('\u0000').first, entry.key.split('\u0000').last),
      ];
      _quiet.clear();
      if (olderAnswers) {
        answer = <String, Object?>{
          ...answer,
          'documents': <Object?>[...(answer['documents']! as List<Object?>), for (final StoredDocument document in quietDocuments) document.entry],
          'deleted': <Object?>[
            ...(answer['deleted']! as List<Object?>),
            for (final (String kind, String id) in quietDeletes) <String, Object?>{'kind': kind, 'id': id},
          ],
        };
      }
      events.change(answer);
      announce(
        'editor',
        documents: <StoredDocument>[...publication.documents, if (olderAnswers) ...quietDocuments],
        deleted: <(String, String)>[...publication.deleted, if (olderAnswers) ...quietDeletes],
        images: publication.images,
        removedImages: publication.removedImages,
      );
      if (!olderAnswers) announce('server', documents: quietDocuments, deleted: quietDeletes);
    }
    lastPublishAnswer = answer;
    return FakeResponse(200, body: answer);
  }

  /// Queues catalog events for a change made by [source]: `editor`, `server` or `file`.
  void announce(
    String source, {
    List<StoredDocument> documents = const <StoredDocument>[],
    List<(String, String)> deleted = const <(String, String)>[],
    List<StoredImage> images = const <StoredImage>[],
    List<String> removedImages = const <String>[],
  }) {
    events.catalog(
      documents: <Map<String, Object?>>[for (final StoredDocument document in documents) <String, Object?>{...document.entry, 'source': source}],
      deleted: <Map<String, Object?>>[for (final (String kind, String id) in deleted) <String, Object?>{'kind': kind, 'id': id, 'source': source}],
      images: <Map<String, Object?>>[for (final StoredImage image in images) <String, Object?>{...image.entry, 'source': source}],
      removedImages: <Map<String, Object?>>[for (final String path in removedImages) <String, Object?>{'path': path, 'source': source}],
    );
  }

  /// A change outside the API (`/__fake/edit`): writes or deletes one
  /// document (a versioned kind gets the next revision) and pushes it. A
  /// [quiet] change is announced only with the next publish, as an in-game
  /// save that lands while a publish is applied.
  Map<String, Object?> serverEdit(GlossKind kind, String id, Object? json, String source, {bool quiet = false}) {
    final StoredDocument? existing = store.document(kind, id);
    if (json == null) {
      if (store.deleteDocument(kind, id, source) == null) throw ApiError('not-found', 'No ${kind.wire} $id.');
      if (quiet) {
        _quiet[documentKey(kind.wire, id)] = null;
      } else {
        announce(source, deleted: <(String, String)>[(kind.wire, id)]);
      }
      return <String, Object?>{'deleted': <String, Object?>{'kind': kind.wire, 'id': id}};
    }
    Object? value = json;
    if (json is String) {
      try {
        value = jsonDecode(json);
      } on FormatException catch (failure) {
        throw invalid('json: not JSON: ${failure.message}');
      }
    }
    if (value is! Map<String, Object?>) throw invalid('json: one JSON object, or its text');
    if (kind.versioned) value = <String, Object?>{...value, 'revision': (existing?.revision ?? 0) + 1};
    String? problem;
    final StoredDocument? document = StoredDocument.of(kind, id, '${const JsonEncoder.withIndent('  ').convert(value)}\n', (String why) => problem = why);
    if (document == null) throw invalid('json: $problem');
    store.putDocument(document, source);
    if (quiet) {
      _quiet[document.key] = document;
    } else {
      announce(source, documents: <StoredDocument>[document]);
    }
    return document.entry;
  }

  /// An image change outside the API (`/__fake/edit` with `image`).
  Map<String, Object?> serverImage(String path, Uint8List? bytes, String source) {
    if (bytes == null) {
      if (store.deleteImage(path) == null) throw ApiError('not-found', 'No image $path.');
      announce(source, removedImages: <String>[path]);
      return <String, Object?>{'removedImage': path};
    }
    final ImageInfo info = imageInfo(bytes) ?? (throw invalid('base64: not a png, jpeg, gif, webp or bmp image'));
    final StoredImage image = store.putImage(StoredImage(path, bytes, info));
    announce(source, images: <StoredImage>[image]);
    return image.entry;
  }

  /// `GET /v1/events`: authenticates the token (the relay forwards the
  /// browser's Authorization; the direct path copies the `auth` frame's).
  EventsOpened openEvents(FakeRequest request, StreamPath path, void Function(Map<String, Object?> event) send, void Function() close) {
    requestCounts['GET /v1/events'] = (requestCounts['GET /v1/events'] ?? 0) + 1;
    try {
      checkOrigin(request);
      final FakeToken token = tokens.authenticate(_bearer(request));
      if (!token.has('read')) throw ApiError('scope-missing', 'This token lacks read.', <String, Object?>{'scope': 'read'});
      final void Function() remove = events.add(EventStream(token, path, send, close, () => notesHash(token)));
      return EventsOpened(FakeResponse(200, headers: cors(request)), <String, Object?>{
        'type': 'open',
        'tokenId': token.id,
        'expiresAt': instant(token.expires),
        'scopes': token.scopes,
        'metaHash': metaHash,
        'notesHash': notesHash(token),
      }, remove);
    } on ApiError catch (refused) {
      if (refused.status == 401) {
        try {
          tokens.pairAttempt(request.address);
        } on ApiError {
          // Already over the limit; the refusal below says enough.
        }
      }
      return EventsOpened(refused.toResponse().withHeaders(cors(request)));
    }
  }

  /// Once a second: tokens past their expiry end, staged uploads age out.
  void housekeeping() {
    tokens.housekeeping();
    changes.housekeeping();
  }
}
