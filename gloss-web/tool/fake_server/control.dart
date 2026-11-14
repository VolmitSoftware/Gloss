import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'direct.dart';
import 'kinds.dart';
import 'protocol.dart';
import 'server.dart';
import 'tokens.dart';

/// Test controls under `http://127.0.0.1:<port>/__fake/`: JSON in and out,
/// no token, loopback callers only, CORS open to any origin.
class ControlPath {
  ControlPath(this.server);

  final FakeGlossServer server;

  Future<void> serve(HttpRequest request) async {
    final InternetAddress? peer = request.connectionInfo?.remoteAddress;
    final String? origin = request.headers.value('origin');
    FakeResponse result;
    if (peer == null || !peer.isLoopback) {
      await request.drain<void>();
      result = const FakeResponse(403, body: <String, Object?>{'error': 'loopback callers only'});
    } else {
      try {
        final String text = await utf8.decoder.bind(request).join();
        final Object? body = text.trim().isEmpty ? null : jsonDecode(text);
        result = request.method == 'OPTIONS' ? const FakeResponse(204) : _route('${request.method} ${request.uri.path}', body);
      } on ApiError catch (refused) {
        result = FakeResponse(refused.status, body: <String, Object?>{'error': refused.message});
      } on FormatException catch (failure) {
        result = FakeResponse(400, body: <String, Object?>{'error': 'invalid JSON body: ${failure.message}'});
      }
    }
    await writeResponse(
      request.response,
      origin == null
          ? result
          : result.withHeaders(<String, String>{
              'Access-Control-Allow-Origin': origin,
              'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
              'Access-Control-Allow-Headers': 'Content-Type',
              'Access-Control-Allow-Private-Network': 'true',
              'Vary': 'Origin',
            }),
    );
    server.log('control ${request.method} ${request.uri.path} ${result.status}');
  }

  static Map<String, Object?> _object(Object? body) {
    if (body == null) return const <String, Object?>{};
    if (body is! Map<String, Object?>) throw invalid('Send a JSON object body.');
    return body;
  }

  static List<String> _strings(Object? value, String name) {
    if (value == null) return const <String>[];
    if (value is! List<Object?> || value.any((Object? item) => item is! String)) throw invalid('$name: a list of strings');
    return value.cast<String>();
  }

  static bool _flag(Map<String, Object?> body, String name) {
    final Object? value = body[name];
    if (value is! bool) throw invalid('$name: true or false');
    return value;
  }

  static String _source(Map<String, Object?> body) {
    final Object source = body['source'] ?? 'server';
    if (source != 'server' && source != 'file') throw invalid('source: server or file');
    return source as String;
  }

  FakeResponse _route(String route, Object? raw) {
    final Map<String, Object?> body = _object(raw);
    switch (route) {
      case 'POST /__fake/pair':
        return FakeResponse(200, body: _pair(body));
      case 'POST /__fake/revoke':
        final Object? id = body['all'] == true ? 'all' : body['tokenId'];
        if (id is! String) throw invalid('tokenId: a token id, or "all"');
        return FakeResponse(200, body: <String, Object?>{'revoked': server.api.tokens.revoke(id)});
      case 'POST /__fake/expire':
        final Object? id = body['tokenId'];
        if (id is! String) throw invalid('tokenId: a token id or a waiting link id');
        final bool found = server.api.tokens.expireNow(id);
        server.api.housekeeping();
        if (!found) return FakeResponse(404, body: <String, Object?>{'error': 'no token or waiting link $id'});
        return FakeResponse(200, body: <String, Object?>{'expired': id});
      case 'POST /__fake/restart':
        final Object downMillis = body['downMillis'] ?? 0;
        if (downMillis is! int || downMillis < 0 || downMillis > 600000) throw invalid('downMillis: 0 to 600000');
        server.restart(Duration(milliseconds: downMillis));
        return FakeResponse(200, body: <String, Object?>{'restarting': true, 'downMillis': downMillis});
      case 'POST /__fake/direct':
        server.directDown = _flag(body, 'down');
        return FakeResponse(200, body: <String, Object?>{'directDown': server.directDown});
      case 'POST /__fake/relay':
        server.relayDown = _flag(body, 'down');
        return FakeResponse(200, body: <String, Object?>{'relayDown': server.relayDown});
      case 'POST /__fake/relay-limit':
        final Object? bytes = body['bytes'];
        if (bytes != null && (bytes is! int || bytes < 1024)) throw invalid('bytes: at least 1024, or null');
        server.relayRequestBytes = bytes as int?;
        return FakeResponse(200, body: <String, Object?>{'relayRequestBytes': bytes});
      case 'POST /__fake/server-on-relay':
        server.serverOnRelay = _flag(body, 'online');
        return FakeResponse(200, body: <String, Object?>{'serverOnRelay': server.serverOnRelay});
      case 'POST /__fake/edit':
        return FakeResponse(200, body: _edit(body));
      case 'GET /__fake/state':
        return FakeResponse(200, body: server.state());
      case 'POST /__fake/latency':
        final Object? millis = body['millis'];
        final Object? answerMillis = body['answerMillis'];
        if (millis == null && answerMillis == null) throw invalid('millis or answerMillis: 0 to 60000');
        for (final Object? value in <Object?>[millis, answerMillis]) {
          if (value != null && (value is! int || value < 0 || value > 60000)) throw invalid('millis and answerMillis: 0 to 60000');
        }
        if (millis != null) server.api.latencyMillis = millis as int;
        if (answerMillis != null) server.api.answerLatencyMillis = answerMillis as int;
        return FakeResponse(200, body: <String, Object?>{'latencyMillis': server.api.latencyMillis, 'answerLatencyMillis': server.api.answerLatencyMillis});
      case 'POST /__fake/older-answers':
        server.api.olderAnswers = _flag(body, 'on');
        return FakeResponse(200, body: <String, Object?>{'olderAnswers': server.api.olderAnswers});
      case 'POST /__fake/origins':
        final List<String> allowed = _strings(body['allowed'], 'allowed');
        server.api.allowedOrigins = allowed;
        return FakeResponse(200, body: <String, Object?>{'allowed': allowed});
      default:
        return FakeResponse(404, body: <String, Object?>{'error': 'no control $route'});
    }
  }

  Map<String, Object?> _pair(Map<String, Object?> body) {
    onlyFields(body, r'$', <String>{'paths', 'scopes', 'folders', 'label', 'expires', 'serverName', 'admin'});
    final PairPaths paths = switch (body['paths'] ?? 'both') {
      'both' => PairPaths.both,
      'direct' => PairPaths.direct,
      'relay' => PairPaths.relay,
      _ => throw invalid('paths: both, direct or relay'),
    };
    final List<String> scopes = _strings(body['scopes'], 'scopes');
    for (final String scope in scopes) {
      if (!allScopes.contains(scope)) throw invalid('Unknown scope $scope; scopes are ${allScopes.join(', ')}');
    }
    final List<String> folders = _strings(body['folders'], 'folders');
    for (final String folder in folders) {
      if (!folderPattern.hasMatch(folder)) throw invalid('folders take folder names such as shop or shop/sub; $folder is not one');
    }
    final Object? label = body['label'];
    if (label != null && (label is! String || label.isEmpty || label.length > 64)) throw invalid('label: 1 to 64 characters');
    final Object? name = body['serverName'];
    if (name != null) {
      if (name is! String || name.trim().isEmpty || name.length > 64) throw invalid('serverName: 1 to 64 characters');
      server.api.serverName = name;
    }
    final Object? admin = body['admin'];
    if (admin != null && admin is! String) throw invalid('admin: a player UUID');
    final PairLink link = server.pair(
      paths: paths,
      scopes: orderedScopes(scopes),
      folders: folders,
      label: label as String?,
      lifetime: parseLifetime(body['expires']),
      admin: admin as String?,
    );
    return link.json;
  }

  Map<String, Object?> _edit(Map<String, Object?> body) {
    final String source = _source(body);
    final Object? image = body['image'];
    if (image != null) {
      onlyFields(body, r'$', <String>{'image', 'base64', 'delete', 'source'});
      if (image is! String || imagePathProblem(image) != null) throw invalid('image: a relative image path such as shop/coin.png');
      if (body['delete'] == true) return server.api.serverImage(image, null, source);
      final Object? data = body['base64'];
      if (data is! String) throw invalid('base64: the image bytes, or delete: true');
      final Uint8List bytes;
      try {
        bytes = base64Decode(data);
      } on FormatException {
        throw invalid('base64: not base64');
      }
      return server.api.serverImage(image, bytes, source);
    }
    onlyFields(body, r'$', <String>{'kind', 'id', 'json', 'delete', 'source', 'quiet', 'afterRead', 'beforeRead'});
    final bool quiet = body['quiet'] == true;
    final Object? kindName = body['kind'];
    final GlossKind kind = (kindName is String ? kindNamed(kindName) : null) ?? (throw invalid('kind: one of ${glossKinds.map((GlossKind kind) => kind.wire).join(', ')}'));
    final Object? id = body['id'];
    if (id is! String || kind.idProblem(id) != null) throw invalid('id: ${id is String ? kind.idProblem(id) : 'a document id'}');
    final Object? json = body['delete'] == true ? null : body['json'];
    if (json == null && body['delete'] != true) throw invalid('json: the body (an object or its text), or delete: true');
    if (body['afterRead'] == true || body['beforeRead'] == true) {
      server.api.editAroundRead(kind.wire, id, () => server.api.serverEdit(kind, id, json, source, quiet: quiet), before: body['beforeRead'] == true);
      return <String, Object?>{'waiting': true};
    }
    return server.api.serverEdit(kind, id, json, source, quiet: quiet);
  }
}
