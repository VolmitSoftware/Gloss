import 'dart:async';

import 'protocol.dart';
import 'tokens.dart';

/// Where an event stream runs: a direct WebSocket or a relay stream id.
enum StreamPath { direct, relay }

/// The most bytes of entries one `catalog` frame carries.
const int catalogFrameBytes = 48 * 1024;

/// 20 frames a second per stream (`eventFramesPerSecond`).
const Duration frameSpacing = Duration(milliseconds: 50);

/// One authenticated event stream. [send] writes one event; [close] ends the
/// stream on its transport; [notesHash] is the token's current notes hash
/// (the fake's meta never changes, so frames never carry `metaHash`).
class EventStream {
  EventStream(this.token, this.path, this.send, this.close, this.notesHash) : _sentNotesHash = notesHash();

  final FakeToken token;
  final StreamPath path;
  final void Function(Map<String, Object?> event) send;
  final void Function() close;
  final String Function() notesHash;
  String _sentNotesHash;

  /// Queued catalog entries by key, oldest first, in frame order: versions,
  /// deletes, images, removed images. A key is in at most one of the two
  /// lists of its pair.
  final List<Map<String, (Map<String, Object?>, int)>> _queues = <Map<String, (Map<String, Object?>, int)>>[
    <String, (Map<String, Object?>, int)>{},
    <String, (Map<String, Object?>, int)>{},
    <String, (Map<String, Object?>, int)>{},
    <String, (Map<String, Object?>, int)>{},
  ];
  Timer? _flush;
  DateTime? _lastFrame;

  bool get _empty => _queues.every((Map<String, (Map<String, Object?>, int)> queue) => queue.isEmpty);
}

/// The `/v1/events` streams. `open`, `change` and `closing` frames go out at
/// once; catalog entries queue per stream, merging per document (a newer
/// version or a delete replaces the queued one), and leave within 50 ms in
/// frames of at most [catalogFrameBytes] of entries.
class EventHub {
  final List<EventStream> _streams = <EventStream>[];

  int get open => _streams.length;

  int openOn(StreamPath path) => _streams.where((EventStream stream) => stream.path == path).length;

  /// Registers a stream. Returns a handle that removes it.
  void Function() add(EventStream stream) {
    _streams.add(stream);
    return () => _remove(stream);
  }

  void _remove(EventStream stream) {
    if (!_streams.remove(stream)) return;
    stream._flush?.cancel();
    stream._flush = null;
  }

  /// A `change` frame with each stream's visible part of the publish answer.
  void change(Map<String, Object?> result) {
    for (final EventStream stream in _streams.toList()) {
      final Map<String, Object?>? visible = _visibleResult(result, stream.token);
      if (visible != null) stream.send(<String, Object?>{'type': 'change', 'result': visible});
    }
  }

  static Map<String, Object?>? _visibleResult(Map<String, Object?> result, FakeToken token) {
    if (token.folders.isEmpty) return result;
    List<Object?> keep(String list, String field) => <Object?>[
          for (final Object? entry in (result[list] as List<Object?>?) ?? const <Object?>[])
            if (entry is Map<String, Object?> && token.sees(entry[field]! as String)) entry,
        ];
    final Map<String, Object?> visible = <String, Object?>{
      ...result,
      'conflicts': keep('conflicts', 'id'),
      'documents': keep('documents', 'id'),
      'deleted': keep('deleted', 'id'),
      'images': keep('images', 'path'),
      'removedImages': keep('removedImages', 'path'),
    };
    final bool any = <String>['documents', 'deleted', 'images', 'removedImages']
        .any((String list) => (visible[list]! as List<Object?>).isNotEmpty);
    return any ? visible : null;
  }

  /// Queues catalog entries, each already carrying its `source`.
  void catalog({
    List<Map<String, Object?>> documents = const <Map<String, Object?>>[],
    List<Map<String, Object?>> deleted = const <Map<String, Object?>>[],
    List<Map<String, Object?>> images = const <Map<String, Object?>>[],
    List<Map<String, Object?>> removedImages = const <Map<String, Object?>>[],
  }) {
    final List<(int, String, Map<String, Object?>, String)> entries = <(int, String, Map<String, Object?>, String)>[
      for (final Map<String, Object?> entry in documents) (0, '${entry['kind']}\u0000${entry['id']}', entry, entry['id']! as String),
      for (final Map<String, Object?> entry in deleted) (1, '${entry['kind']}\u0000${entry['id']}', entry, entry['id']! as String),
      for (final Map<String, Object?> entry in images) (2, entry['path']! as String, entry, entry['path']! as String),
      for (final Map<String, Object?> entry in removedImages) (3, entry['path']! as String, entry, entry['path']! as String),
    ];
    if (entries.isEmpty) return;
    for (final EventStream stream in _streams) {
      for (final (int queue, String key, Map<String, Object?> entry, String reach) in entries) {
        if (!stream.token.sees(reach)) continue;
        // A version and a delete of one key replace each other.
        stream._queues[queue ^ 1].remove(key);
        stream._queues[queue].remove(key);
        stream._queues[queue][key] = (entry, jsonBytes(entry));
      }
      _schedule(stream);
    }
  }

  void _schedule(EventStream stream) {
    if (stream._flush != null || stream._empty) return;
    final DateTime? last = stream._lastFrame;
    final Duration wait = last == null ? Duration.zero : frameSpacing - now().difference(last);
    stream._flush = Timer(wait.isNegative ? Duration.zero : wait, () => _sendCatalog(stream));
  }

  void _sendCatalog(EventStream stream) {
    stream._flush = null;
    const List<String> names = <String>['documents', 'deleted', 'images', 'removedImages'];
    final Map<String, Object?> frame = <String, Object?>{'type': 'catalog'};
    int bytes = 0;
    for (int index = 0; index < names.length; index++) {
      final Map<String, (Map<String, Object?>, int)> queue = stream._queues[index];
      final List<Map<String, Object?>> taken = <Map<String, Object?>>[];
      for (final String key in queue.keys.toList()) {
        final (Map<String, Object?> entry, int size) = queue[key]!;
        if (bytes > 0 && bytes + size > catalogFrameBytes) break;
        taken.add(entry);
        bytes += size;
        queue.remove(key);
      }
      if (taken.isNotEmpty) frame[names[index]] = taken;
      if (queue.isNotEmpty) break;
    }
    final String notesHash = stream.notesHash();
    if (notesHash != stream._sentNotesHash) {
      frame['notesHash'] = notesHash;
      stream._sentNotesHash = notesHash;
    }
    stream._lastFrame = now();
    stream.send(frame);
    _schedule(stream);
  }

  /// Sends `closing` with [reason] to the token's streams and closes them.
  void closeToken(String tokenId, String reason) => closeWhere((EventStream stream) => stream.token.id == tokenId, reason);

  void closeAll(String reason) => closeWhere((EventStream stream) => true, reason);

  void closeWhere(bool Function(EventStream stream) which, String reason) {
    for (final EventStream stream in _streams.where(which).toList()) {
      _remove(stream);
      stream.send(<String, Object?>{'type': 'closing', 'reason': reason});
      stream.close();
    }
  }

  /// Closes streams without a `closing` frame, as a lost connection does.
  void dropWhere(bool Function(EventStream stream) which) {
    for (final EventStream stream in _streams.where(which).toList()) {
      _remove(stream);
      stream.close();
    }
  }
}
