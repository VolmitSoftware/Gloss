/// Editor API 1 over one [ServerConnection] (sync-relay/README.md, editor API
/// sections 9 to 12): the catalog, bodies, images, meta, notes, history and
/// publish, each answer typed and each refusal turned into an
/// [EditorSyncFailure] with one sentence and the next step. Pure Dart.
library;

import 'dart:async';
import 'dart:convert';

import 'editor_sync.dart';
import 'server/error_copy.dart';
import 'server/server_connection.dart';
import 'server/transport.dart';

/// One image's bytes as `POST /v1/images/read` sends them.
typedef SyncImageData = ({String hash, String mediaType, List<int> bytes});

class EditorApi {
  EditorApi(this.connection, {this.origin});

  final ServerConnection connection;

  /// This page's origin, for the `origin-not-allowed` sentence.
  final String? origin;

  bool get _relay => connection.status.path == ConnectionPath.relay;

  /// The largest body page worth asking for on the current path.
  int get pageBytes =>
      _relay ? huiEditorSyncRelayPageBytes : huiEditorSyncDirectPageBytes;

  Future<ApiResponse> _send(ApiRequest request, {bool retry = true}) async {
    try {
      return retry
          ? await connection.requestRetrying(request)
          : await connection.request(request);
    } on NotConnected catch (error) {
      throw EditorSyncFailure(
        error.status.message ?? transportCopy('dropped').text,
        code: error.status.problem?.code ?? 'dropped',
      );
    } on TransportFailure catch (failure) {
      throw EditorSyncFailure(failure.message, code: failure.code);
    }
  }

  EditorSyncFailure _refused(ApiResponse response) => EditorSyncFailure(
    response.problem(origin: origin).text,
    code: response.errorCode ?? codeForStatus(response.status),
    status: response.status,
    error: response.error,
  );

  Map<String, Object?> _body(ApiResponse response) {
    if (!response.ok) throw _refused(response);
    final Object? body = response.body;
    if (body is! Map<String, Object?>) {
      throw EditorSyncFailure(
        transportCopy('dropped').text,
        code: 'malformed',
        status: response.status,
      );
    }
    return body;
  }

  static String _query(Map<String, String?> values) {
    final List<String> parts = <String>[
      for (final MapEntry<String, String?> entry in values.entries)
        if (entry.value != null)
          '${entry.key}=${Uri.encodeQueryComponent(entry.value!)}',
    ];
    return parts.isEmpty ? '' : '?${parts.join('&')}';
  }

  /// Every page of the catalog, merged; [onPage] sees each as it lands.
  Future<SyncCatalog> catalog({void Function(SyncCatalog page)? onPage}) async {
    final List<SyncDocumentRow> documents = <SyncDocumentRow>[];
    final List<SyncImageRow> images = <SyncImageRow>[];
    String? after;
    String? metaHash;
    String? notesHash;
    do {
      final SyncCatalog page = SyncCatalog.decode(
        _body(
          await _send(
            ApiRequest(
              'GET',
              '/v1/catalog${_query(<String, String?>{'after': after})}',
            ),
          ),
        ),
      );
      documents.addAll(page.documents);
      images.addAll(page.images);
      metaHash = page.metaHash ?? metaHash;
      notesHash = page.notesHash ?? notesHash;
      onPage?.call(page);
      after = page.next;
    } while (after != null);
    return SyncCatalog(
      documents: documents,
      images: images,
      metaHash: metaHash,
      notesHash: notesHash,
    );
  }

  /// One page of bodies in catalog order (section 10.2), at most
  /// [maxBytes] (the path's [pageBytes] when null).
  Future<(List<SyncDocumentBody>, String?)> documentPage({
    String? after,
    int? maxBytes,
  }) async {
    final Map<String, Object?> body = _body(
      await _send(
        ApiRequest(
          'GET',
          '/v1/documents${_query(<String, String?>{'after': after, 'maxBytes': '${maxBytes ?? pageBytes}'})}',
        ),
      ),
    );
    final Object? documents = body['documents'];
    return (
      <SyncDocumentBody>[
        if (documents is List)
          for (final Object? entry in documents) SyncDocumentBody.decode(entry),
      ],
      body['next'] is String ? body['next'] as String : null,
    );
  }

  /// Bodies of [keys] by batch read (section 10.3): chunks of 200, up to
  /// four calls in flight, `remaining` asked again. [onBatch] sees each
  /// answer as it lands. Keys the server no longer has come back in
  /// `missing`; refs it refused (folder, size) in `failed`.
  Future<
    ({List<SyncDocumentBody> bodies, List<String> missing, List<String> failed})
  >
  readDocuments(
    Iterable<String> keys, {
    void Function(List<SyncDocumentBody> bodies)? onBatch,
  }) async {
    final List<String> queue = keys.toSet().toList();
    final List<SyncDocumentBody> bodies = <SyncDocumentBody>[];
    final List<String> missing = <String>[];
    final List<String> failed = <String>[];
    Future<void> worker() async {
      while (queue.isNotEmpty) {
        final List<String> chunk = queue
            .take(huiEditorSyncMaxReadRefs)
            .toList();
        queue.removeRange(0, chunk.length);
        final Map<String, Object?> body = _body(
          await _send(
            ApiRequest(
              'POST',
              '/v1/documents/read',
              body: <String, Object?>{
                'refs': <Map<String, String>>[
                  for (final String key in chunk)
                    <String, String>{
                      'kind': editorSyncKeyKind(key),
                      'id': editorSyncKeyId(key),
                    },
                ],
              },
            ),
          ),
        );
        final List<SyncDocumentBody> landed = <SyncDocumentBody>[
          if (body['documents'] is List)
            for (final Object? entry in body['documents'] as List)
              SyncDocumentBody.decode(entry),
        ];
        bodies.addAll(landed);
        if (landed.isNotEmpty) onBatch?.call(landed);
        if (body['errors'] is List) {
          for (final Object? entry in body['errors'] as List) {
            if (entry is! Map ||
                entry['kind'] is! String ||
                entry['id'] is! String) {
              continue;
            }
            final String key = editorSyncKey(
              entry['kind'] as String,
              entry['id'] as String,
            );
            (entry['code'] == 'not-found' ? missing : failed).add(key);
          }
        }
        if (body['remaining'] is List) {
          for (final Object? entry in body['remaining'] as List) {
            if (entry is Map &&
                entry['kind'] is String &&
                entry['id'] is String) {
              queue.add(
                editorSyncKey(entry['kind'] as String, entry['id'] as String),
              );
            }
          }
        }
      }
    }

    final int workers = queue.length <= huiEditorSyncMaxReadRefs ? 1 : 4;
    await Future.wait(<Future<void>>[
      for (int index = 0; index < workers; index++) worker(),
    ]);
    return (bodies: bodies, missing: missing, failed: failed);
  }

  /// Image bytes by hash (section 10.4): 64 per call, two calls in flight.
  /// [onBatch] sees each answer's images as they land.
  Future<List<SyncImageData>> readImages(
    Iterable<String> hashes, {
    void Function(List<SyncImageData> images)? onBatch,
  }) async {
    final List<String> queue = hashes.toSet().toList();
    final List<SyncImageData> images = <SyncImageData>[];
    Future<void> worker() async {
      while (queue.isNotEmpty) {
        final List<String> chunk = queue
            .take(huiEditorSyncMaxReadImages)
            .toList();
        queue.removeRange(0, chunk.length);
        final Map<String, Object?> body = _body(
          await _send(
            ApiRequest(
              'POST',
              '/v1/images/read',
              body: <String, Object?>{'hashes': chunk},
            ),
          ),
        );
        final List<SyncImageData> landed = <SyncImageData>[
          if (body['images'] is List)
            for (final Object? entry in body['images'] as List)
              if (entry is Map &&
                  entry['hash'] is String &&
                  entry['mediaType'] is String &&
                  entry['data'] is String)
                (
                  hash: entry['hash'] as String,
                  mediaType: entry['mediaType'] as String,
                  bytes: base64Decode(entry['data'] as String),
                ),
        ];
        images.addAll(landed);
        if (landed.isNotEmpty) onBatch?.call(landed);
        if (body['remaining'] is List) {
          for (final Object? entry in body['remaining'] as List) {
            if (entry is String) queue.add(entry);
          }
        }
      }
    }

    await Future.wait(<Future<void>>[worker(), worker()]);
    return images;
  }

  /// Uploads one image (section 10.5); returns its hash.
  Future<String> uploadImage(String mediaType, List<int> bytes) async {
    final Map<String, Object?> body = _body(
      await _send(
        ApiRequest(
          'POST',
          '/v1/images',
          body: <String, Object?>{
            'mediaType': mediaType,
            'data': base64Encode(bytes),
          },
        ),
      ),
    );
    return body['hash'] as String;
  }

  Future<SyncMeta> meta() async {
    final Map<String, Object?> body = _body(
      await _send(const ApiRequest('GET', '/v1/meta')),
    );
    return SyncMeta.decode(
      body['meta'],
      hash: body['metaHash'] is String ? body['metaHash'] as String : null,
    );
  }

  Future<SyncNotes> notes() async {
    final Map<String, Object?> body = _body(
      await _send(const ApiRequest('GET', '/v1/notes')),
    );
    return SyncNotes.decode(
      body['notes'],
      hash: body['notesHash'] is String ? body['notesHash'] as String : null,
    );
  }

  /// A stored version's body, or null when the server no longer keeps it.
  Future<String?> history(String kind, String id, int version) async {
    final Map<String, Object?> body = _body(
      await _send(
        ApiRequest(
          'GET',
          '/v1/history${_query(<String, String?>{'kind': kind, 'id': id, 'version': '$version'})}',
        ),
      ),
    );
    return body['found'] == true && body['json'] is String
        ? body['json'] as String
        : null;
  }

  /// Sends a changeset (section 12). Not retried on `busy`: a retry goes out
  /// with the same idempotency key from the caller.
  Future<SyncPublishResult> publish(Map<String, Object?> body) async {
    final ApiResponse response = await _send(
      ApiRequest('POST', '/v1/changes', body: body, counted: true),
      retry: false,
    );
    return SyncPublishResult.decode(_body(response));
  }
}
