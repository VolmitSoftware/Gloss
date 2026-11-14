/// Runs the fake Gloss editor server.
///
///     dart run tool/fake_server/main.dart --port 9806 [--data <plugins/Gloss>]
///         [--editor-url http://127.0.0.1:8808] [--allowed-origin <origin> ...]
///         [--server-name "Twin Peaks"] [--paths both|direct|relay] [--verbose]
///
/// Prints the first pairing link on stdout as `link <url>`; with --verbose,
/// one line per request on stderr. See server.dart for what it serves.
library;

import 'dart:async';
import 'dart:io';

import 'server.dart';

Future<void> main(List<String> arguments) async {
  int port = 9806;
  String? data;
  String editorUrl = 'http://127.0.0.1:8808';
  final List<String> origins = <String>[];
  String serverName = 'Twin Peaks';
  PairPaths paths = PairPaths.both;
  bool verbose = false;
  Never usage(String problem) {
    stderr.writeln('$problem\nUsage: dart run tool/fake_server/main.dart --port 9806 [--data <dir>] [--editor-url <url>] '
        '[--allowed-origin <origin> ...] [--server-name <name>] [--paths both|direct|relay] [--verbose]');
    exit(64);
  }

  for (int index = 0; index < arguments.length; index++) {
    final String argument = arguments[index];
    final int equals = argument.indexOf('=');
    final String name = equals < 0 ? argument : argument.substring(0, equals);
    if (name == '--verbose') {
      verbose = true;
      continue;
    }
    String value() {
      if (equals >= 0) return argument.substring(equals + 1);
      if (index + 1 >= arguments.length) usage('$name needs a value');
      return arguments[++index];
    }

    switch (name) {
      case '--port':
        port = int.tryParse(value()) ?? usage('--port takes a number');
      case '--data':
        data = value();
      case '--editor-url':
        editorUrl = value();
      case '--allowed-origin':
        origins.add(value());
      case '--server-name':
        serverName = value();
      case '--paths':
        final String chosen = value();
        paths = PairPaths.values.firstWhere((PairPaths path) => path.name == chosen, orElse: () => usage('--paths takes both, direct or relay'));
      default:
        usage('Unknown argument $argument');
    }
  }
  final FakeGlossServer server = await FakeGlossServer.start(
    port: port,
    dataDirectory: data,
    editorUrl: editorUrl,
    allowedOrigins: origins.isEmpty ? null : origins,
    serverName: serverName,
    verbose: verbose,
  );
  stderr.writeln('Fake Gloss server on ${server.apiUrl}, relay ${server.relayUrl}/app, controls ${server.directUrl}/__fake/; '
      '${server.api.store.documents.length} documents and ${server.api.store.images.length} images from ${data ?? 'the built-in set'}; '
      'fingerprint $fakeFingerprint');
  stdout.writeln('link ${server.pair(paths: paths).link}');
  final Completer<void> stopping = Completer<void>();
  void stop(ProcessSignal signal) {
    if (!stopping.isCompleted) stopping.complete();
  }

  final List<StreamSubscription<ProcessSignal>> signals = <StreamSubscription<ProcessSignal>>[
    ProcessSignal.sigint.watch().listen(stop),
    if (!Platform.isWindows) ProcessSignal.sigterm.watch().listen(stop),
  ];
  await stopping.future;
  for (final StreamSubscription<ProcessSignal> signal in signals) {
    await signal.cancel();
  }
  await server.close();
}
