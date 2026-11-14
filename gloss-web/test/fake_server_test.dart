/// The fake Gloss editor server (tool/fake_server) against the pairing
/// contract: pairing, reads, publishes, events on both paths and restarts.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:gloss_editor/services/editor_sync.dart' show editorSyncBytesHash, editorSyncDocumentHash;
import 'package:test/test.dart';

import '../tool/fake_server/server.dart';

const String origin = 'http://127.0.0.1:8808';

class Answer {
  Answer(this.status, this.body, this.headers);

  final int status;
  final Object? body;
  final HttpHeaders headers;

  Map<String, Object?> get json => body! as Map<String, Object?>;

  Map<String, Object?> get error => json['error']! as Map<String, Object?>;

  List<Map<String, Object?>> list(String name) => (json[name]! as List<Object?>).cast<Map<String, Object?>>();
}

Future<Answer> call(FakeGlossServer server, String method, String path,
    {String? token, Object? body, Map<String, String> headers = const <String, String>{}, String? from = origin}) async {
  final HttpClient client = HttpClient();
  try {
    final HttpClientRequest request = await client.openUrl(method, Uri.parse('${server.directUrl}$path'));
    if (from != null) request.headers.set('Origin', from);
    if (token != null) request.headers.set('Authorization', 'Bearer $token');
    headers.forEach(request.headers.set);
    if (body != null) {
      request.headers.contentType = ContentType.json;
      request.add(utf8.encode(jsonEncode(body)));
    }
    final HttpClientResponse response = await request.close();
    final String text = await utf8.decodeStream(response);
    return Answer(response.statusCode, text.isEmpty ? null : jsonDecode(text), response.headers);
  } finally {
    client.close(force: true);
  }
}

Future<Map<String, Object?>> control(FakeGlossServer server, String name, [Object? body]) async {
  final bool read = name == 'state';
  final Answer answer = await call(server, read ? 'GET' : 'POST', '/__fake/$name', body: read ? null : body ?? <String, Object?>{}, from: null);
  expect(answer.status, 200, reason: '/__fake/$name answered ${answer.body}');
  return answer.json;
}

/// Mints a link through the controls and exchanges it. Returns the token and the pair answer.
Future<(String, Map<String, Object?>)> pairToken(FakeGlossServer server, [Map<String, Object?> options = const <String, Object?>{}]) async {
  final Map<String, Object?> code = decodePairingCode((await control(server, 'pair', options))['code']! as String);
  final Answer answer = await call(server, 'POST', '/gloss/v1/pair', body: <String, Object?>{'tokenId': code['tokenId'], 'secret': code['secret'], 'client': 'Dart on VM'});
  expect(answer.status, 200, reason: '${answer.body}');
  return (answer.json['token']! as String, answer.json);
}

int _counter = 0;

Future<Answer> publish(FakeGlossServer server, String token, Map<String, Object?> changes, {int? counter, String? key}) => call(
      server,
      'POST',
      '/gloss/v1/changes',
      token: token,
      headers: <String, String>{'X-Gloss-Counter': '${counter ?? ++_counter}'},
      body: <String, Object?>{'idempotencyKey': key ?? 'pub_${DateTime.now().microsecondsSinceEpoch}_${_counter}x', ...changes},
    );

Future<Map<String, Object?>> catalogEntry(FakeGlossServer server, String token, String kind, String id) async {
  final Answer catalog = await call(server, 'GET', '/gloss/v1/catalog', token: token);
  return catalog.list('documents').firstWhere((Map<String, Object?> entry) => entry['kind'] == kind && entry['id'] == id);
}

Future<String> bodyOf(FakeGlossServer server, String token, String kind, String id) async =>
    (await call(server, 'GET', '/gloss/v1/documents/$kind/$id', token: token)).json['json']! as String;

String withChange(String json, Map<String, Object?> change) =>
    jsonEncode(<String, Object?>{...jsonDecode(json) as Map<String, Object?>, ...change});

/// Frames of a WebSocket, decoded, taken in order or by a test.
class Frames {
  Frames(Stream<Object?> source) {
    source.listen((Object? data) {
      _frames.add(jsonDecode(data! as String) as Map<String, Object?>);
      _wake();
    }, onDone: () {
      done = true;
      _wake();
    });
  }

  final List<Map<String, Object?>> _frames = <Map<String, Object?>>[];
  bool done = false;
  Completer<void>? _signal;

  void _wake() {
    final Completer<void>? signal = _signal;
    _signal = null;
    signal?.complete();
  }

  Future<Map<String, Object?>> next() => take((Map<String, Object?> frame) => true);

  Future<Map<String, Object?>> take(bool Function(Map<String, Object?> frame) test, {Duration timeout = const Duration(seconds: 3)}) async {
    final DateTime until = DateTime.now().add(timeout);
    while (true) {
      final int index = _frames.indexWhere(test);
      if (index >= 0) return _frames.removeAt(index);
      if (done) throw StateError('closed without the frame; had $_frames');
      final Duration left = until.difference(DateTime.now());
      if (left.isNegative) throw TimeoutException('no such frame; had $_frames');
      await (_signal ??= Completer<void>()).future.timeout(left, onTimeout: () {});
    }
  }
}

Future<(WebSocket, Frames)> directEvents(FakeGlossServer server, String token) async {
  final WebSocket socket = await WebSocket.connect('${server.relayUrl}/gloss/v1/events', headers: <String, Object>{'Origin': origin});
  final Frames frames = Frames(socket);
  socket.add(jsonEncode(<String, Object?>{'type': 'auth', 'token': token}));
  return (socket, frames);
}

String challenge() => base64Url.encode(List<int>.generate(32, (int index) => Random.secure().nextInt(256))).replaceAll('=', '');

Future<bool> proves(String challenge, String serverKey, String proof) async {
  final List<int> key = base64Url.decode(base64Url.normalize(serverKey));
  if ('sha256:${sha256.convert(key)}' != fakeFingerprint) return false;
  return Ed25519().verify(
    utf8.encode('$editorProofPrefix$challenge'),
    signature: Signature(base64Url.decode(base64Url.normalize(proof)), publicKey: SimplePublicKey(key, type: KeyPairType.ed25519)),
  );
}

void main() {
  late FakeGlossServer server;

  setUp(() async => server = await FakeGlossServer.start());
  tearDown(() => server.close());

  group('pairing', () {
    test('a link pairs once, then answers pairing-invalid', () async {
      final Map<String, Object?> link = await control(server, 'pair', <String, Object?>{'label': 'laptop'});
      expect(link['link'], startsWith('$origin/#/pair/GLS1.'));
      final Map<String, Object?> code = decodePairingCode(link['link']! as String);
      expect(code.keys, <String>['v', 'directUrl', 'relayUrl', 'fingerprint', 'serverKey', 'tokenId', 'secret', 'expiresAt', 'label', 'serverName', 'scopes']);
      expect(code['directUrl'], server.directUrl);
      expect(code['relayUrl'], server.relayUrl);
      expect(code['scopes'], defaultScopes);
      final Map<String, Object?> exchange = <String, Object?>{'tokenId': code['tokenId'], 'secret': code['secret']};
      final Answer paired = await call(server, 'POST', '/gloss/v1/pair', body: exchange);
      expect(paired.status, 200);
      expect(paired.json['token'], matches(RegExp(r'^gls_tok_[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{43}$')));
      expect(paired.json.keys, <String>['token', 'tokenId', 'scopes', 'expiresAt', 'lifetimeSeconds', 'fingerprint', 'counter', 'serverName']);
      expect(paired.json['scopes'], isNot(contains('publish:commands')));
      expect(paired.json['lifetimeSeconds'], 604800);
      final Answer again = await call(server, 'POST', '/gloss/v1/pair', body: exchange);
      expect(again.status, 401);
      expect(again.error['code'], 'pairing-invalid');
    });

    test('an expired link answers pairing-expired; codes carry folders and one path', () async {
      final Map<String, Object?> link = await control(server, 'pair', <String, Object?>{'paths': 'relay', 'folders': <String>['shop'], 'scopes': <String>['read']});
      final Map<String, Object?> code = decodePairingCode(link['code']! as String);
      expect(code.containsKey('directUrl'), isFalse);
      expect(code['folders'], <String>['shop']);
      expect(code['scopes'], <String>['read']);
      await control(server, 'expire', <String, Object?>{'tokenId': link['tokenId']});
      final Answer late = await call(server, 'POST', '/gloss/v1/pair', body: <String, Object?>{'tokenId': code['tokenId'], 'secret': code['secret']});
      expect(late.status, 410);
      expect(late.error['code'], 'pairing-expired');
    });

    test('hello signs a challenge with the key the fingerprint names', () async {
      final SimplePublicKey key = await (await Ed25519().newKeyPairFromSeed(fakeSeed)).extractPublicKey();
      expect(base64Url.encode(key.bytes).replaceAll('=', ''), fakeServerKey);
      final String asked = challenge();
      final Answer hello = await call(server, 'GET', '/gloss/v1/hello', headers: <String, String>{challengeHeader: asked});
      expect(hello.json, containsPair('fingerprint', fakeFingerprint));
      expect(hello.json['serverName'], 'Twin Peaks');
      expect(await proves(asked, hello.headers.value(serverKeyHeader)!, hello.headers.value(proofHeader)!), isTrue);
      expect(await proves(challenge(), hello.headers.value(serverKeyHeader)!, hello.headers.value(proofHeader)!), isFalse);
    });

    test('tokens slide on use and answer token-expired, token-revoked or unauthorized', () async {
      final (String token, Map<String, Object?> paired) = await pairToken(server, <String, Object?>{'expires': '1m'});
      expect(paired['lifetimeSeconds'], 60);
      await Future<void>.delayed(const Duration(milliseconds: 1100));
      final Answer info = await call(server, 'GET', '/gloss/v1/token', token: token);
      expect(DateTime.parse(info.json['expiresAt']! as String).isAfter(DateTime.parse(paired['expiresAt']! as String)), isTrue);
      await control(server, 'expire', <String, Object?>{'tokenId': paired['tokenId']});
      expect((await call(server, 'GET', '/gloss/v1/token', token: token)).error['code'], 'token-expired');
      final (String other, Map<String, Object?> second) = await pairToken(server);
      await control(server, 'revoke', <String, Object?>{'tokenId': second['tokenId']});
      final Answer revoked = await call(server, 'GET', '/gloss/v1/catalog', token: other);
      expect((revoked.status, revoked.error['code']), (401, 'token-revoked'));
      expect((await call(server, 'GET', '/gloss/v1/catalog', token: 'gls_nope')).error['code'], 'unauthorized');
    });

    test('CORS, origins, unknown paths and other versions', () async {
      final Answer preflight = await call(server, 'OPTIONS', '/gloss/v1/catalog', headers: <String, String>{'Access-Control-Request-Method': 'GET'});
      expect(preflight.status, 204);
      expect(preflight.headers.value('access-control-allow-origin'), origin);
      expect(preflight.headers.value('access-control-allow-headers'), 'Authorization, Content-Type, X-Gloss-Counter, X-Gloss-Challenge, If-None-Match');
      expect(preflight.headers.value('access-control-allow-private-network'), 'true');
      expect(preflight.headers.value('access-control-expose-headers'), 'ETag, Retry-After');
      final Answer refused = await call(server, 'GET', '/gloss/v1/hello', from: 'http://localhost:9999');
      expect((refused.status, refused.error['code']), (403, 'origin-not-allowed'));
      expect(refused.headers.value('access-control-allow-origin'), isNull);
      await control(server, 'origins', <String, Object?>{'allowed': <String>['http://localhost:9999']});
      expect((await call(server, 'GET', '/gloss/v1/hello', from: 'http://localhost:9999')).status, 200);
      expect((await call(server, 'GET', '/gloss/v1/nothing', from: null)).error['code'], 'not-found');
      expect((await call(server, 'GET', '/gloss/v2/hello', from: null)).error['code'], 'unsupported-protocol');
    });
  });

  group('reading', () {
    test('catalog pages cover a loaded data folder, with summaries and warnings', () async {
      final Directory data = await Directory.systemTemp.createTemp('fake_gloss_');
      addTearDown(() => data.delete(recursive: true));
      void write(String path, String text) {
        final File file = File('${data.path}/$path');
        file.parent.createSync(recursive: true);
        file.writeAsStringSync(text);
      }

      for (int index = 0; index < 1100; index++) {
        write('holograms/h$index.json', jsonEncode(<String, Object?>{'revision': 1, 'name': '&6Holo §l$index', 'lines': <String>['x']}));
      }
      final String big = 'x' * 400000;
      for (int index = 0; index < 3; index++) {
        write('holograms/big-$index.json', jsonEncode(<String, Object?>{'revision': 1, 'lines': <String>[big]}));
      }
      write('holograms/broken.json', '{"revision": 1,');
      final String shopMain = jsonEncode(<String, Object?>{
        'title': '&aShop',
        'components': <Object?>[
          <String, Object?>{'actions': <Object?>[<String, Object?>{'type': 'command', 'source': 'server', 'command': 'say hi'}]},
        ],
      });
      write('menus/shop/main.json', shopMain);
      write('menus/lobby/hub.json', '{"components": []}');
      write('menus/flat.json', '{"components": []}');
      write('motd.json', '{"revision": 3, "lines": []}');
      (File('${data.path}/images/shop/coin.png')..createSync(recursive: true)).writeAsBytesSync(solidPng(16, 16, <int>[1, 2, 3]));
      File('${data.path}/images/logo.png').writeAsBytesSync(solidPng(4, 4, <int>[9, 9, 9]));
      final FakeGlossServer loaded = await FakeGlossServer.start(dataDirectory: data.path);
      addTearDown(loaded.close);
      final (String token, _) = await pairToken(loaded);
      final List<Map<String, Object?>> documents = <Map<String, Object?>>[];
      final List<Map<String, Object?>> images = <Map<String, Object?>>[];
      String? after;
      int pages = 0;
      do {
        final Answer page = await call(loaded, 'GET', '/gloss/v1/catalog${after == null ? '' : '?after=$after'}', token: token);
        expect(page.json['metaHash'], startsWith('sha256:'));
        expect(page.json['notesHash'], startsWith('sha256:'));
        expect(page.list('documents').length + page.list('images').length, lessThanOrEqualTo(1000));
        documents.addAll(page.list('documents'));
        images.addAll(page.list('images'));
        after = page.json['next'] as String?;
        pages++;
      } while (after != null);
      expect(pages, 2);
      expect(documents, hasLength(1100 + 3 + 3 + 1));
      expect(images.map((Map<String, Object?> image) => image['path']), <String>['logo.png', 'shop/coin.png']);
      expect(images.last['hash'], editorSyncBytesHash(solidPng(16, 16, <int>[1, 2, 3])));
      expect(images.last, containsPair('width', 16));
      final List<String> keys = <String>[for (final Map<String, Object?> entry in documents) '${entry['kind']}\u0000${entry['id']}'];
      expect(keys, List<String>.of(keys)..sort());
      Map<String, Object?> entry(String kind, String id) => documents.firstWhere((Map<String, Object?> entry) => entry['kind'] == kind && entry['id'] == id);
      expect(entry('hologram', 'h7')['summary'], <String, Object?>{'name': 'Holo 7'});
      expect(entry('hologram', 'h7')['revision'], 1);
      expect(entry('menu', 'shop/main'), containsPair('summary', <String, Object?>{'name': 'Shop', 'folder': 'shop', 'commands': true}));
      expect(entry('menu', 'shop/main')['hash'], editorSyncDocumentHash(shopMain));
      expect(entry('menu', 'shop/main').containsKey('revision'), isFalse);
      expect(entry('menu', 'flat')['summary'], <String, Object?>{'name': 'flat'});
      expect(entry('motd', 'motd')['path'], 'motd.json');
      final Answer notes = await call(loaded, 'GET', '/gloss/v1/notes', token: token);
      expect((notes.json['notes']! as Map<String, Object?>)['warnings'], <Object?>[startsWith('unreadable|hologram|broken||')]);
      final Answer batch = await call(loaded, 'POST', '/gloss/v1/documents/read', token: token, body: <String, Object?>{
        'refs': <Object?>[for (int index = 0; index < 3; index++) <String, Object?>{'kind': 'hologram', 'id': 'big-$index'}],
      });
      expect(batch.list('documents'), hasLength(2));
      expect(batch.json['remaining'], <Object?>[<String, Object?>{'kind': 'hologram', 'id': 'big-2'}]);
    });

    test('body pages, batch and single reads', () async {
      final (String token, _) = await pairToken(server);
      final Answer catalog = await call(server, 'GET', '/gloss/v1/catalog', token: token);
      final List<Map<String, Object?>> bodies = <Map<String, Object?>>[];
      String? after;
      int pages = 0;
      do {
        final Answer page = await call(server, 'GET', '/gloss/v1/documents?maxBytes=600${after == null ? '' : '&after=$after'}', token: token);
        expect(page.status, 200, reason: '${page.body}');
        bodies.addAll(page.list('documents'));
        after = page.json['next'] as String?;
        pages++;
      } while (after != null);
      expect(pages, greaterThan(1));
      expect(bodies.map((Map<String, Object?> body) => body['hash']), catalog.list('documents').map((Map<String, Object?> entry) => entry['hash']));
      for (final Map<String, Object?> body in bodies) {
        expect(editorSyncDocumentHash(body['json']! as String), body['hash']);
      }
      final Answer batch = await call(server, 'POST', '/gloss/v1/documents/read', token: token, body: <String, Object?>{
        'refs': <Object?>[
          <String, Object?>{'kind': 'hologram', 'id': 'welcome'},
          <String, Object?>{'kind': 'menu', 'id': 'shop/gone'},
          <String, Object?>{'kind': 'nope', 'id': 'x'},
          <String, Object?>{'kind': 'hologram', 'id': 'a/b'},
        ],
      });
      expect(batch.list('documents').single, containsPair('summary', <String, Object?>{'name': 'Welcome'}));
      expect(batch.list('errors').map((Map<String, Object?> error) => error['code']), <String>['not-found', 'invalid-request', 'invalid-request']);
      final Answer twice = await call(server, 'POST', '/gloss/v1/documents/read', token: token, body: <String, Object?>{
        'refs': <Object?>[<String, Object?>{'kind': 'hologram', 'id': 'welcome'}, <String, Object?>{'kind': 'hologram', 'id': 'welcome'}],
      });
      expect(twice.error['code'], 'invalid-request');
      final Answer single = await call(server, 'GET', '/gloss/v1/documents/menu/shop/sub/hats', token: token);
      expect(single.json['summary'], <String, Object?>{'name': 'Hats', 'folder': 'shop/sub'});
      expect((await call(server, 'GET', '/gloss/v1/documents/menu/shop/none', token: token)).status, 404);
    });

    test('images, meta and notes answer with ETags', () async {
      final (String token, _) = await pairToken(server);
      final Answer catalog = await call(server, 'GET', '/gloss/v1/catalog', token: token);
      final Map<String, Object?> coin = catalog.list('images').firstWhere((Map<String, Object?> image) => image['path'] == 'shop/coin.png');
      final String hex = (coin['hash']! as String).substring(7);
      final Answer image = await call(server, 'GET', '/gloss/v1/images/$hex', token: token);
      expect(editorSyncBytesHash(base64Decode(image.json['data']! as String)), coin['hash']);
      expect(image.headers.value('etag'), '"$hex"');
      expect((await call(server, 'GET', '/gloss/v1/images/$hex', token: token, headers: <String, String>{'If-None-Match': '"$hex"'})).status, 304);
      final String unknown = 'sha256:${'0' * 64}';
      final Answer read = await call(server, 'POST', '/gloss/v1/images/read', token: token, body: <String, Object?>{'hashes': <String>[coin['hash']! as String, unknown]});
      expect(read.list('images').single['mediaType'], 'image/png');
      expect(read.json['missing'], <String>[unknown]);
      final Answer meta = await call(server, 'GET', '/gloss/v1/meta', token: token);
      final List<Object?> kinds = (meta.json['meta']! as Map<String, Object?>)['kinds']! as List<Object?>;
      expect(kinds, hasLength(27));
      expect(kinds, contains(equals(<String, Object?>{'kind': 'menu', 'storage': 'menus', 'layout': 'tree', 'versioned': false, 'singletonId': null, 'scope': 'publish:screens'})));
      expect(meta.json['metaHash'], catalog.json['metaHash']);
      expect((await call(server, 'GET', '/gloss/v1/meta', token: token, headers: <String, String>{'If-None-Match': meta.headers.value('etag')!})).status, 304);
      final Answer notes = await call(server, 'GET', '/gloss/v1/notes', token: token);
      expect(notes.headers.value('etag'), '"${notes.json['notesHash']}"');
    });
  });

  group('publishing', () {
    test('puts, creates and deletes land; versioned kinds get the next revision', () async {
      final (String token, _) = await pairToken(server);
      final Map<String, Object?> welcome = await catalogEntry(server, token, 'hologram', 'welcome');
      final Map<String, Object?> hub = await catalogEntry(server, token, 'menu', 'lobby/hub');
      final String before = await bodyOf(server, token, 'hologram', 'welcome');
      final Answer answer = await publish(server, token, <String, Object?>{
        'documents': <Object?>[
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'welcome', 'baseHash': welcome['hash'], 'json': withChange(before, <String, Object?>{'lines': <String>['&6Hello']})},
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'brand-new', 'baseHash': null, 'json': '{"revision":1,"lines":["new"]}'},
          <String, Object?>{'op': 'delete', 'kind': 'menu', 'id': 'lobby/hub', 'baseHash': hub['hash']},
        ],
      });
      expect(answer.status, 200, reason: '${answer.body}');
      expect(answer.json['status'], 'applied');
      expect(answer.json['message'], 'Published 3 changes.');
      expect(answer.json['changeId'], startsWith('chg_'));
      expect(answer.list('documents').map((Map<String, Object?> entry) => (entry['id'], entry['revision'])), <(String, int)>[('welcome', 2), ('brand-new', 1)]);
      expect(answer.json['deleted'], <Object?>[<String, Object?>{'kind': 'menu', 'id': 'lobby/hub'}]);
      expect(jsonDecode(await bodyOf(server, token, 'hologram', 'welcome')), containsPair('revision', 2));
      final Answer notes = await call(server, 'GET', '/gloss/v1/notes', token: token);
      final Map<String, Object?> row = (((notes.json['notes']! as Map<String, Object?>)['history']! as List<Object?>).first! as Map<String, Object?>);
      expect(row, containsPair('kind', 'holograms'));
      final Answer history = await call(server, 'GET', '/gloss/v1/history?kind=holograms&id=welcome&version=${row['version']}', token: token);
      expect((history.json['found'], history.json['json']), (true, before));
    });

    test('conflicts name changed, deleted and exists; invalid bodies are rejected', () async {
      final (String token, _) = await pairToken(server);
      final Map<String, Object?> diner = await catalogEntry(server, token, 'hologram', 'diner');
      final String dinerBody = await bodyOf(server, token, 'hologram', 'diner');
      final Answer conflicted = await publish(server, token, <String, Object?>{
        'documents': <Object?>[
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'diner', 'baseHash': 'sha256:${'1' * 64}', 'json': withChange(dinerBody, <String, Object?>{'lines': <String>['stale']})},
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'gone', 'baseHash': 'sha256:${'2' * 64}', 'json': '{"revision":1}'},
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'welcome', 'baseHash': null, 'json': '{"revision":1}'},
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'sawmill', 'baseHash': (await catalogEntry(server, token, 'hologram', 'sawmill'))['hash'], 'json': '{"revision":7}'},
        ],
      });
      expect(conflicted.json['status'], 'conflict');
      expect(conflicted.list('conflicts').map((Map<String, Object?> entry) => '${entry['id']} ${entry['reason']}'), <String>['diner changed', 'gone deleted', 'welcome exists', 'sawmill changed']);
      expect(conflicted.list('conflicts').first['name'], 'Double R Diner');
      expect(conflicted.json['message'], startsWith('Nothing published; 4 kept the server copy: '));
      final Answer mixed = await publish(server, token, <String, Object?>{
        'documents': <Object?>[
          <String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'diner', 'baseHash': diner['hash'], 'json': withChange(dinerBody, <String, Object?>{'lines': <String>['pie']})},
          <String, Object?>{'op': 'delete', 'kind': 'hologram', 'id': 'welcome', 'baseHash': 'sha256:${'3' * 64}'},
        ],
      });
      expect(mixed.json['status'], 'applied');
      expect(mixed.json['message'], 'Published 1 change; 1 kept the server copy: Welcome.');
      expect(mixed.list('documents').single['revision'], 3);
      final Answer rejected = await publish(server, token, <String, Object?>{
        'documents': <Object?>[<String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'fresh', 'baseHash': null, 'json': '{"lines":[]}'}],
      });
      expect((rejected.json['status'], rejected.json['message']), ('rejected', 'hologram fresh: revision must be a positive whole number'));
    });

    test('an idempotency key answers once, and an equal put is no conflict', () async {
      final (String token, _) = await pairToken(server);
      final Map<String, Object?> welcome = await catalogEntry(server, token, 'hologram', 'welcome');
      final String body = withChange(await bodyOf(server, token, 'hologram', 'welcome'), <String, Object?>{'lines': <String>['once']});
      final Map<String, Object?> changes = <String, Object?>{
        'documents': <Object?>[<String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'welcome', 'baseHash': welcome['hash'], 'json': body}],
      };
      final Answer first = await publish(server, token, changes, key: 'pub_retry_000000001');
      final Answer second = await publish(server, token, changes, key: 'pub_retry_000000001');
      expect(second.json, first.json);
      expect((await catalogEntry(server, token, 'hologram', 'welcome'))['revision'], 2);
      await control(server, 'restart', <String, Object?>{'downMillis': 0});
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final Answer afterRestart = await publish(server, token, changes, key: 'pub_retry_000000001');
      expect(afterRestart.json['status'], 'applied');
      expect(afterRestart.list('documents').single['revision'], 2);
    });

    test('X-Gloss-Counter must grow', () async {
      final (String token, Map<String, Object?> paired) = await pairToken(server);
      expect(paired['counter'], 0);
      final Map<String, Object?> changes = <String, Object?>{
        'documents': <Object?>[<String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'c1', 'baseHash': null, 'json': '{"revision":1}'}],
      };
      expect((await publish(server, token, changes, counter: 5)).status, 200);
      final Answer replayed = await publish(server, token, changes, counter: 5);
      expect((replayed.status, replayed.error['code'], replayed.error['counter']), (409, 'counter-replayed', 5));
      final Answer missing = await call(server, 'POST', '/gloss/v1/changes', token: token, body: <String, Object?>{'idempotencyKey': 'pub_nocounter_0001', ...changes});
      expect((missing.status, missing.error['code']), (400, 'invalid-request'));
    });

    test('scopes and folders refuse whole calls and hide entries', () async {
      final (String screens, _) = await pairToken(server, <String, Object?>{'scopes': <String>['read', 'publish:screens']});
      final Map<String, Object?> shop = await catalogEntry(server, screens, 'menu', 'shop/main');
      final Answer world = await publish(server, screens, <String, Object?>{
        'documents': <Object?>[<String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'x', 'baseHash': null, 'json': '{"revision":1}'}],
      });
      expect((world.status, world.error['code'], world.error['scope']), (403, 'scope-missing', 'publish:world'));
      final Map<String, Object?> commandPut = <String, Object?>{
        'documents': <Object?>[
          <String, Object?>{'op': 'put', 'kind': 'menu', 'id': 'shop/main', 'baseHash': shop['hash'], 'json': withChange(await bodyOf(server, screens, 'menu', 'shop/main'), <String, Object?>{'title': 'Shop 2'})},
        ],
      };
      final Answer commands = await publish(server, screens, commandPut);
      expect((commands.error['code'], commands.error['scope']), ('scope-missing', 'publish:commands'));
      expect(commands.error['message'], contains('menu shop/main'));
      expect((await call(server, 'GET', '/gloss/v1/history?kind=holograms&id=welcome&version=1', token: screens)).error['scope'], 'history');
      final (String trusted, _) = await pairToken(server, <String, Object?>{'scopes': <String>['read', 'publish:screens', 'publish:commands']});
      expect((await publish(server, trusted, commandPut)).json['status'], 'applied');

      final (String folder, Map<String, Object?> paired) = await pairToken(server, <String, Object?>{'folders': <String>['shop']});
      expect(paired['folders'], <String>['shop']);
      final Answer catalog = await call(server, 'GET', '/gloss/v1/catalog', token: folder);
      expect(catalog.list('documents').map((Map<String, Object?> entry) => entry['id']), <String>['shop/main', 'shop/sub/hats', 'shop/flow']);
      expect(catalog.list('images').map((Map<String, Object?> entry) => entry['path']), <String>['shop/coin.png']);
      final Answer denied = await call(server, 'GET', '/gloss/v1/documents/menu/lobby/hub', token: folder);
      expect((denied.status, denied.error['code']), (403, 'folder-denied'));
      expect(denied.error['folders'], <String>['shop']);
      final Answer batch = await call(server, 'POST', '/gloss/v1/documents/read', token: folder, body: <String, Object?>{
        'refs': <Object?>[<String, Object?>{'kind': 'menu', 'id': 'lobby/hub'}],
      });
      expect(batch.list('errors').single['code'], 'folder-denied');
      final Answer outside = await publish(server, folder, <String, Object?>{
        'documents': <Object?>[<String, Object?>{'op': 'put', 'kind': 'menu', 'id': 'lobby/new', 'baseHash': null, 'json': '{}'}],
      });
      expect((outside.status, outside.error['code']), (403, 'folder-denied'));
    });

    test('images upload once and publish by hash', () async {
      final (String token, _) = await pairToken(server);
      final Uint8List png = solidPng(24, 24, <int>[10, 200, 30]);
      final Map<String, Object?> upload = <String, Object?>{'mediaType': 'image/png', 'data': base64Encode(png)};
      final Answer staged = await call(server, 'POST', '/gloss/v1/images', token: token, body: upload);
      expect(staged.status, 201);
      expect(staged.json, <String, Object?>{'hash': editorSyncBytesHash(png), 'size': png.length, 'mediaType': 'image/png', 'width': 24, 'height': 24});
      expect((await call(server, 'POST', '/gloss/v1/images', token: token, body: upload)).status, 200);
      final Answer wrong = await call(server, 'POST', '/gloss/v1/images', token: token, body: <String, Object?>{'mediaType': 'image/gif', 'data': base64Encode(png)});
      expect(wrong.error['message'], 'bytes do not match image/gif');
      final Answer missing = await publish(server, token, <String, Object?>{
        'images': <Object?>[<String, Object?>{'op': 'put', 'path': 'shop/x.png', 'baseHash': null, 'hash': 'sha256:${'4' * 64}', 'mediaType': 'image/png'}],
      });
      expect(missing.error['missing'], <String>['sha256:${'4' * 64}']);
      final Answer landed = await publish(server, token, <String, Object?>{
        'images': <Object?>[<String, Object?>{'op': 'put', 'path': 'shop/new.png', 'baseHash': null, 'hash': staged.json['hash'], 'mediaType': 'image/png'}],
      });
      expect(landed.json['images'], <Object?>[<String, Object?>{'path': 'shop/new.png', ...staged.json}]);
      final Answer catalog = await call(server, 'GET', '/gloss/v1/catalog', token: token);
      expect(catalog.list('images').map((Map<String, Object?> image) => image['path']), contains('shop/new.png'));
    });
  });

  group('events', () {
    test('direct stream: open, change before catalog, server edits, closing on revoke', () async {
      final (String token, Map<String, Object?> paired) = await pairToken(server);
      final (WebSocket socket, Frames frames) = await directEvents(server, token);
      final Map<String, Object?> open = await frames.next();
      expect(open.keys, <String>['type', 'tokenId', 'expiresAt', 'scopes', 'metaHash', 'notesHash']);
      expect((open['type'], open['tokenId']), ('open', paired['tokenId']));
      final Map<String, Object?> welcome = await catalogEntry(server, token, 'hologram', 'welcome');
      final String body = withChange(await bodyOf(server, token, 'hologram', 'welcome'), <String, Object?>{'lines': <String>['live']});
      await publish(server, token, <String, Object?>{
        'documents': <Object?>[<String, Object?>{'op': 'put', 'kind': 'hologram', 'id': 'welcome', 'baseHash': welcome['hash'], 'json': body}],
      });
      final Map<String, Object?> change = await frames.next();
      expect((change['type'], (change['result']! as Map<String, Object?>)['status']), ('change', 'applied'));
      final Stopwatch clock = Stopwatch()..start();
      final Map<String, Object?> catalog = await frames.next();
      expect(clock.elapsedMilliseconds, lessThan(50));
      expect(catalog['type'], 'catalog');
      expect((catalog['documents']! as List<Object?>).single, containsPair('source', 'editor'));
      expect(catalog['notesHash'], isNot(open['notesHash']));
      await control(server, 'edit', <String, Object?>{'kind': 'hologram', 'id': 'diner', 'json': <String, Object?>{'lines': <String>['pie']}});
      final Map<String, Object?> edited = await frames.next();
      expect((edited['documents']! as List<Object?>).single, allOf(containsPair('source', 'server'), containsPair('revision', 3)));
      await control(server, 'edit', <String, Object?>{'image': 'logo.png', 'delete': true, 'source': 'file'});
      expect((await frames.next())['removedImages'], <Object?>[<String, Object?>{'path': 'logo.png', 'source': 'file'}]);
      await control(server, 'revoke', <String, Object?>{'tokenId': paired['tokenId']});
      expect(await frames.next(), <String, Object?>{'type': 'closing', 'reason': 'token-revoked'});
      await socket.done;
      expect(socket.closeCode, 1000);
    });

    test('a refused stream closes with 4000 plus the status', () async {
      final (WebSocket socket, Frames frames) = await directEvents(server, 'gls_tok_AAAAAAAAAAAAAAAA.${'A' * 43}');
      await socket.done;
      expect((socket.closeCode, socket.closeReason), (4401, 'unauthorized'));
      expect(frames._frames, isEmpty);
      final (String token, Map<String, Object?> paired) = await pairToken(server);
      final (WebSocket open, Frames openFrames) = await directEvents(server, token);
      expect((await openFrames.next())['type'], 'open');
      await control(server, 'expire', <String, Object?>{'tokenId': paired['tokenId']});
      expect(await openFrames.next(), <String, Object?>{'type': 'closing', 'reason': 'token-expired'});
      await open.done;
    });

    test('relay emulation: subscribe, proof, requests, events and a restart', () async {
      final (String token, _) = await pairToken(server);
      final WebSocket relay = await WebSocket.connect('${server.relayUrl}/app', headers: <String, Object>{'Origin': 'https://anywhere.example'});
      addTearDown(relay.close);
      final Frames frames = Frames(relay);
      void request(int id, String method, String path, {Map<String, String> headers = const <String, String>{}, Object? body}) => relay.add(jsonEncode(<String, Object?>{
            'type': 'request',
            'id': id,
            'method': method,
            'path': path,
            'headers': <String, String>{'Authorization': 'Bearer $token', ...headers},
            'body': ?body,
          }));
      Future<Map<String, Object?>> response(int id) => frames.take((Map<String, Object?> frame) => frame['type'] == 'response' && frame['id'] == id);

      relay.add(jsonEncode(<String, Object?>{'type': 'subscribe', 'protocol': 1, 'fingerprint': fakeFingerprint}));
      expect(await frames.next(), <String, Object?>{'type': 'subscribed', 'fingerprint': fakeFingerprint, 'online': true});
      final String asked = challenge();
      request(1, 'GET', '/v1/hello', headers: <String, String>{challengeHeader: asked});
      final Map<String, Object?> hello = await response(1);
      final Map<String, Object?> headers = hello['headers']! as Map<String, Object?>;
      expect(await proves(asked, headers[serverKeyHeader]! as String, headers[proofHeader]! as String), isTrue);
      request(2, 'GET', '/v1/catalog');
      expect(((await response(2))['body']! as Map<String, Object?>)['documents'], isNotEmpty);
      request(3, 'GET', '/v1/events');
      expect((await response(3))['status'], 200);
      expect(await frames.next(), allOf(containsPair('type', 'event'), containsPair('id', 3)));
      await control(server, 'edit', <String, Object?>{'kind': 'menu', 'id': 'shop/new', 'json': '{"components":[]}'});
      final Map<String, Object?> pushed = await frames.next();
      expect((pushed['event']! as Map<String, Object?>)['documents'], <Object?>[containsPair('id', 'shop/new')]);

      await control(server, 'restart', <String, Object?>{'downMillis': 300});
      expect(await frames.next(), <String, Object?>{'type': 'event', 'id': 3, 'event': <String, Object?>{'type': 'closing', 'reason': 'server-stopping'}});
      expect(await frames.next(), <String, Object?>{'type': 'close', 'id': 3});
      expect(await frames.next(), <String, Object?>{'type': 'status', 'online': false});
      request(4, 'GET', '/v1/token');
      expect(((await response(4))['body']! as Map<String, Object?>)['error'], containsPair('code', 'server-offline'));
      await expectLater(call(server, 'GET', '/gloss/v1/hello'), throwsA(anything));
      expect(await frames.next(), <String, Object?>{'type': 'status', 'online': true});
      expect((await call(server, 'GET', '/gloss/v1/token', token: token)).status, 200);

      await control(server, 'server-on-relay', <String, Object?>{'online': false});
      expect(await frames.next(), <String, Object?>{'type': 'status', 'online': false});
      final Map<String, Object?> state = await control(server, 'state');
      expect(state, containsPair('documents', 11));
      expect((state['requests']! as Map<String, Object?>)['GET /v1/catalog'], 1);
    });
  });
}
