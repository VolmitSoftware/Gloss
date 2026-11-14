library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../config/defaults.dart';
import '../l10n/hui_localizations.dart';
import '../model/model.dart';
import 'workspace.dart';

sealed class WorkspaceRoute {
  const WorkspaceRoute();
}

final class WorkspaceDocumentRoute extends WorkspaceRoute {
  const WorkspaceDocumentRoute({
    required this.workspaceId,
    required this.documentId,
  });

  final String workspaceId;
  final String documentId;
}

final class WorkspaceMenuImportRoute extends WorkspaceRoute {
  const WorkspaceMenuImportRoute(this.envelope);

  final WorkspaceMenuImportEnvelope envelope;
}

/// `#/` or no hash: the start page.
final class WorkspaceStartRoute extends WorkspaceRoute {
  const WorkspaceStartRoute();
}

/// `#/pair/<code>` (a pairing link) or `#/pair` once the code was read.
final class WorkspacePairRoute extends WorkspaceRoute {
  const WorkspacePairRoute(this.code);

  /// The `GLS1.` code, or empty once it moved into memory.
  final String code;
}

/// `#/workspace`: the browser workspace with no document named.
final class WorkspaceLocalRoute extends WorkspaceRoute {
  const WorkspaceLocalRoute();
}

/// `#/server/<id>`, or `#/server/<id>/document/<documentId>`: a paired
/// server's workspace.
final class WorkspaceServerRoute extends WorkspaceRoute {
  const WorkspaceServerRoute(this.serverId, {this.documentId});

  /// The first 16 hex digits of the server's fingerprint.
  final String serverId;
  final String? documentId;
}

final class WorkspaceMenuImportEnvelope {
  const WorkspaceMenuImportEnvelope({
    required this.runtimeId,
    required this.json,
  });

  static const int version = 1;
  static const String kind = 'menu';

  final String runtimeId;
  final String json;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'version': version,
    'kind': kind,
    'runtimeId': runtimeId,
    'json': json,
  };
}

final class WorkspaceRouteResult {
  const WorkspaceRouteResult({this.route, String? error}) : _error = error;

  final WorkspaceRoute? route;
  final String? _error;

  String? get error => _error == null ? null : huiText(_error);
}

String workspaceDocumentHash(String workspaceId, String documentId) =>
    '#/workspace/$workspaceId/document/$documentId';

/// The hash of a paired server's workspace, or of one document in it.
String serverWorkspaceHash(String serverId, [String? documentId]) =>
    documentId == null
    ? '#/server/$serverId'
    : '#/server/$serverId/document/$documentId';

final RegExp _serverIdPattern = RegExp(r'^[0-9a-f]{16}$');

String workspaceMenuImportHash(WorkspaceMenuImportEnvelope envelope) {
  final List<int> encoded = utf8.encode(jsonEncode(envelope.toJson()));
  final List<int> compressed = const GZipEncoder().encodeBytes(encoded);
  return '#/import/menu/${base64Url.encode(compressed).replaceAll('=', '')}';
}

WorkspaceRouteResult parseWorkspaceRoute(String rawHash) {
  final String hash = rawHash.trim();
  if (hash.isEmpty || hash == '#' || hash == '#/') {
    return const WorkspaceRouteResult(route: WorkspaceStartRoute());
  }
  if (!hash.startsWith('#/')) {
    return const WorkspaceRouteResult(error: 'The editor link is malformed.');
  }
  if (hash == '#/pair') {
    return const WorkspaceRouteResult(route: WorkspacePairRoute(''));
  }
  if (hash.startsWith('#/pair/')) {
    return WorkspaceRouteResult(
      route: WorkspacePairRoute(hash.substring('#/pair/'.length)),
    );
  }
  if (hash == '#/workspace') {
    return const WorkspaceRouteResult(route: WorkspaceLocalRoute());
  }
  final List<String> segments = hash.substring(2).split('/');
  if (segments[0] == 'server' &&
      (segments.length == 2 ||
          (segments.length == 4 && segments[2] == 'document'))) {
    final String serverId = segments[1];
    final String? documentId = segments.length == 4 ? segments[3] : null;
    if (!_serverIdPattern.hasMatch(serverId) ||
        (documentId != null && !isWorkspaceUuid(documentId))) {
      return const WorkspaceRouteResult(
        error: 'The workspace link contains an invalid id.',
      );
    }
    return WorkspaceRouteResult(
      route: WorkspaceServerRoute(serverId, documentId: documentId),
    );
  }
  if (segments.length == 4 &&
      segments[0] == 'workspace' &&
      segments[2] == 'document') {
    final String workspaceId = segments[1];
    final String documentId = segments[3];
    if (!isWorkspaceUuid(workspaceId) || !isWorkspaceUuid(documentId)) {
      return const WorkspaceRouteResult(
        error: 'The workspace link contains an invalid id.',
      );
    }
    return WorkspaceRouteResult(
      route: WorkspaceDocumentRoute(
        workspaceId: workspaceId,
        documentId: documentId,
      ),
    );
  }
  if (segments.length == 3 &&
      segments[0] == 'import' &&
      segments[1] == WorkspaceMenuImportEnvelope.kind) {
    return _decodeMenuEnvelope(segments[2]);
  }
  return const WorkspaceRouteResult(error: 'The editor link is not supported.');
}

WorkspaceRouteResult _decodeMenuEnvelope(String payload) {
  if (payload.isEmpty || payload.length > _maxEncodedEnvelopeLength) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff payload is empty or too large.',
    );
  }
  final List<int> compressed;
  try {
    final int padding = (4 - payload.length % 4) % 4;
    final String suffix = List<String>.filled(padding, '=').join();
    compressed = base64Url.decode('$payload$suffix');
  } catch (_) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff payload is not valid base64url.',
    );
  }
  final List<int> decoded;
  try {
    decoded = _decodeBoundedGzipMember(compressed);
  } on _DecodedEnvelopeTooLarge {
    return const WorkspaceRouteResult(
      error: 'The decoded menu handoff is too large.',
    );
  } catch (_) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff payload is not valid gzip data.',
    );
  }
  final Object? raw;
  try {
    raw = jsonDecode(utf8.decode(decoded));
  } catch (_) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff envelope is not valid UTF-8 JSON.',
    );
  }
  if (raw is! Map) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff envelope must be a JSON object.',
    );
  }
  if (raw['version'] != WorkspaceMenuImportEnvelope.version ||
      raw['kind'] != WorkspaceMenuImportEnvelope.kind) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff envelope version or kind is unsupported.',
    );
  }
  final Object? runtimeId = raw['runtimeId'];
  final Object? json = raw['json'];
  if (runtimeId is! String || !isCanonicalMenuId(runtimeId)) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff runtime id is not canonical.',
    );
  }
  if (json is! String || json.isEmpty || json.length > _maxMenuJsonLength) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff JSON is empty or too large.',
    );
  }
  try {
    decodeHuiMenu(json);
  } catch (_) {
    return const WorkspaceRouteResult(
      error: 'The menu handoff does not contain valid Gloss menu JSON.',
    );
  }
  return WorkspaceRouteResult(
    route: WorkspaceMenuImportRoute(
      WorkspaceMenuImportEnvelope(runtimeId: runtimeId, json: json),
    ),
  );
}

Uint8List _decodeBoundedGzipMember(List<int> compressed) {
  final InputMemoryStream input = InputMemoryStream(compressed);
  _readGzipHeader(input);
  final _BoundedOutputStream output = _BoundedOutputStream(
    _maxDecodedEnvelopeLength,
  );
  Inflate.stream(input, output: output);
  if (input.length < 8) {
    throw const FormatException('Missing gzip footer.');
  }
  final int expectedCrc = input.readUint32();
  final int expectedLength = input.readUint32();
  if (!input.isEOS) {
    throw const FormatException(
      'Concatenated gzip members and trailing bytes are not supported.',
    );
  }
  if (expectedLength > _maxDecodedEnvelopeLength) {
    throw const _DecodedEnvelopeTooLarge();
  }
  final Uint8List decoded = output.getBytes();
  if (expectedLength != decoded.length || getCrc32(decoded) != expectedCrc) {
    throw const FormatException('The gzip checksum or size is invalid.');
  }
  return decoded;
}

void _readGzipHeader(InputMemoryStream input) {
  if (input.length < 10 ||
      input.readByte() != 0x1f ||
      input.readByte() != 0x8b ||
      input.readByte() != 8) {
    throw const FormatException('Invalid gzip header.');
  }
  final int flags = input.readByte();
  if ((flags & 0xe0) != 0) {
    throw const FormatException('Invalid gzip flags.');
  }
  input.skip(6);
  if ((flags & 0x04) != 0) {
    if (input.length < 2) throw const FormatException('Invalid gzip extra.');
    final int extraLength = input.readUint16();
    if (input.length < extraLength) {
      throw const FormatException('Invalid gzip extra.');
    }
    input.skip(extraLength);
  }
  if ((flags & 0x08) != 0) _skipGzipString(input);
  if ((flags & 0x10) != 0) _skipGzipString(input);
  if ((flags & 0x02) != 0) {
    if (input.length < 2) {
      throw const FormatException('Invalid gzip header checksum.');
    }
    input.skip(2);
  }
  if (input.length < 9) {
    throw const FormatException('Missing gzip body or footer.');
  }
}

void _skipGzipString(InputMemoryStream input) {
  while (!input.isEOS) {
    if (input.readByte() == 0) return;
  }
  throw const FormatException('Unterminated gzip header string.');
}

final class _BoundedOutputStream extends OutputStream {
  _BoundedOutputStream(this.maximumLength)
    : _buffer = Uint8List(
        maximumLength < _initialCapacity ? maximumLength : _initialCapacity,
      ),
      super(byteOrder: ByteOrder.littleEndian);

  static const int _initialCapacity = 32 * 1024;

  final int maximumLength;
  Uint8List _buffer;
  int _length = 0;

  @override
  int get length => _length;

  @override
  void clear() {
    _length = 0;
  }

  @override
  void flush() {}

  @override
  Uint8List getBytes() => subset(0, _length);

  @override
  Uint8List subset(int start, [int? end]) {
    final int resolvedStart = start < 0 ? _length + start : start;
    final int requestedEnd = end ?? _length;
    final int resolvedEnd = requestedEnd < 0
        ? _length + requestedEnd
        : requestedEnd;
    if (resolvedStart < 0 ||
        resolvedEnd < resolvedStart ||
        resolvedEnd > _length) {
      throw const FormatException('Invalid inflate output range.');
    }
    return Uint8List.view(
      _buffer.buffer,
      _buffer.offsetInBytes + resolvedStart,
      resolvedEnd - resolvedStart,
    );
  }

  @override
  void writeByte(int value) {
    _ensureCapacity(1);
    _buffer[_length++] = value;
  }

  @override
  void writeBytes(List<int> bytes, {int? length}) {
    final int count = length ?? bytes.length;
    if (count < 0 || count > bytes.length) {
      throw const FormatException('Invalid inflate output length.');
    }
    _ensureCapacity(count);
    _buffer.setRange(_length, _length + count, bytes);
    _length += count;
  }

  @override
  void writeStream(InputStream stream) {
    final int count = stream.length;
    _ensureCapacity(count);
    _buffer.setRange(_length, _length + count, stream.toUint8List());
    _length += count;
  }

  void _ensureCapacity(int additionalLength) {
    final int required = _length + additionalLength;
    if (additionalLength < 0 || required > maximumLength) {
      throw const _DecodedEnvelopeTooLarge();
    }
    if (required <= _buffer.length) return;
    int capacity = _buffer.isEmpty ? 1 : _buffer.length;
    while (capacity < required) {
      final int doubled = capacity * 2;
      capacity = doubled > maximumLength ? maximumLength : doubled;
    }
    final Uint8List expanded = Uint8List(capacity)
      ..setRange(0, _length, _buffer);
    _buffer = expanded;
  }
}

final class _DecodedEnvelopeTooLarge implements Exception {
  const _DecodedEnvelopeTooLarge();
}

const int _maxEncodedEnvelopeLength = 48_000;
const int _maxDecodedEnvelopeLength = 2 * 1024 * 1024;
const int _maxMenuJsonLength = 2 * 1024 * 1024;
