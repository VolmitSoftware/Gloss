import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:gloss_sync_relay/src/frame_guard.dart';
import 'package:gloss_sync_relay/src/relay_address.dart';
import 'package:gloss_sync_relay/src/relay_broker.dart';
import 'package:gloss_sync_relay/src/relay_channel_sink.dart';
import 'package:gloss_sync_relay/src/relay_frame.dart';
import 'package:gloss_sync_relay/src/relay_limits.dart';
import 'package:gloss_sync_relay/src/relay_sessions.dart';
import 'package:gloss_sync_relay/src/server_identity.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:stream_channel/stream_channel.dart';
import 'package:web_socket_channel/io.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// The browser origins of the hosted Gloss web editor: its own domain and the
/// two Firebase Hosting domains behind it.
const List<String> defaultAllowedAppOrigins = <String>[
  'https://gloss.volmitsoftware.com',
  'https://gloss-editor.web.app',
  'https://gloss-editor.firebaseapp.com',
];

/// A running relay: its HTTP listener and every WebSocket it holds.
final class RelayServer {
  final HttpServer _http;
  final Set<WebSocket> _sockets;

  RelayServer._(this._http, this._sockets);

  int get port => _http.port;

  InternetAddress get address => _http.address;

  /// Stops accepting, then closes every server and browser WebSocket with
  /// 1001 (going away) so each side reconnects at once, and waits at most
  /// [grace] for them to answer. Upgraded sockets have left the HTTP server,
  /// so closing the listener alone would leave them open.
  Future<void> close({Duration grace = const Duration(seconds: 2)}) async {
    await _http.close(force: true);
    final List<WebSocket> open = _sockets.toList();
    await Future.wait(<Future<void>>[
      for (final WebSocket socket in open)
        socket
            .close(WebSocketStatus.goingAway, 'relay stopping')
            .then((Object? _) {}, onError: (Object error) {}),
    ]).timeout(grace, onTimeout: () => <void>[]);
  }
}

/// Starts the relay: `/agent` for Gloss servers, `/app` for browsers running
/// the Gloss web editor and `GET /healthz` with connection counts.
///
/// Browser handshakes must carry an `Origin` equal to one of
/// [allowedAppOrigins]. Server handshakes must carry none, since servers are not
/// browsers. [trustedProxyHops] is how many proxies in front of the relay
/// append to `X-Forwarded-For` (see [clientAddress]).
Future<RelayServer> startRelayServer({
  required int port,
  String address = '0.0.0.0',
  RelayLimits limits = const RelayLimits(),
  Iterable<String> allowedAppOrigins = defaultAllowedAppOrigins,
  ChallengeVerifier? verifier,
  int trustedProxyHops = 1,
}) async {
  final RelayBroker broker = RelayBroker(limits: limits);
  final ChallengeVerifier challenges = verifier ?? ChallengeVerifier();
  final Set<String> origins = normalizeOrigins(allowedAppOrigins);
  final Set<WebSocket> sockets = <WebSocket>{};
  int agentConnections = 0;
  int appConnections = 0;
  final _AddressCounts agentsByAddress = _AddressCounts();
  final _AddressCounts appsByAddress = _AddressCounts();

  void releaseAgent(String address) {
    agentConnections--;
    agentsByAddress.remove(address);
  }

  void releaseApp(String address) {
    appConnections--;
    appsByAddress.remove(address);
  }

  Handler agentHandler(String address) => _upgrade(limits, sockets, (
    WebSocketChannel channel,
  ) {
    final ChannelSink sink = ChannelSink(
      channel.sink,
      limits.maxAgentOutboundQueue,
    );
    final AgentSession session = AgentSession(
      broker: broker,
      verifier: challenges,
      sink: sink,
    );
    Future<void> processing = Future<void>.value();
    channel.stream.listen(
      (Object? message) {
        if (message is! String) {
          session.onDone();
          sink.closeWithError(RelayErrorCode.invalidFrame, 'text frames only');
          return;
        }
        processing = processing.then((void _) => session.onText(message));
      },
      onDone: () {
        releaseAgent(address);
        sink.markClosed();
        session.onDone();
      },
      onError: (Object error) {},
      cancelOnError: false,
    );
  }, () => releaseAgent(address));

  Handler appHandler(String origin, String clientAddress) =>
      _upgrade(limits, sockets, (WebSocketChannel channel) {
        final ChannelSink sink = ChannelSink(
          channel.sink,
          limits.maxAppOutboundQueue,
        );
        final AppSession session = AppSession(
          broker: broker,
          sink: sink,
          origin: origin,
          address: clientAddress,
        );
        channel.stream.listen(
          (Object? message) {
            if (message is! String) {
              session.onDone();
              sink.closeWithError(
                RelayErrorCode.invalidFrame,
                'text frames only',
              );
              return;
            }
            session.onText(message);
          },
          onDone: () {
            releaseApp(clientAddress);
            sink.markClosed();
            session.onDone();
          },
          onError: (Object error) {},
          cancelOnError: false,
        );
      }, () => releaseApp(clientAddress));

  FutureOr<Response> handler(Request request) {
    final String path = request.url.path;
    final String? origin = request.headers['origin'];
    if (path == 'agent') {
      if (origin != null) return _json(403, 'servers send no Origin');
      final String address = clientAddress(request, trustedProxyHops);
      if (agentConnections >= limits.maxAgents * 2 ||
          agentsByAddress[address] >= limits.maxAgentConnectionsPerAddress) {
        return _json(503, 'too many connections');
      }
      agentConnections++;
      agentsByAddress.add(address);
      return agentHandler(address)(request);
    }
    if (path == 'app') {
      if (origin == null || !origins.contains(origin.toLowerCase())) {
        return _json(403, 'origin not allowed');
      }
      final String address = clientAddress(request, trustedProxyHops);
      if (appConnections >= limits.maxApps ||
          appsByAddress[address] >= limits.maxAppConnectionsPerAddress) {
        return _json(503, 'too many connections');
      }
      appConnections++;
      appsByAddress.add(address);
      return appHandler(origin.toLowerCase(), address)(request);
    }
    if (path == 'healthz' && request.method == 'GET') {
      return Response.ok(
        jsonEncode(<String, Object?>{
          'status': 'ok',
          'protocol': relayProtocol,
          'servers': broker.onlineCount,
          'apps': broker.appCount,
          'pending': broker.pendingCount,
          'streams': broker.streamCount,
        }),
        headers: _jsonHeaders,
      );
    }
    return _json(404, 'not found');
  }

  final HttpServer http = await shelf_io.serve(
    handler,
    address,
    port,
    poweredByHeader: null,
  );
  return RelayServer._(http, sockets);
}

const Map<String, String> _jsonHeaders = <String, String>{
  HttpHeaders.contentTypeHeader: 'application/json; charset=utf-8',
  HttpHeaders.cacheControlHeader: 'no-store',
};

Response _json(int status, String message) => Response(
  status,
  body: jsonEncode(<String, Object?>{'error': message}),
  headers: _jsonHeaders,
);

/// The caller's address. Behind [trustedProxyHops] proxies it is the
/// `X-Forwarded-For` entry the outermost one appended (the last entry behind
/// Cloud Run's single front end); earlier entries come from the client and are
/// ignored. With no trusted proxy, or when that entry is missing or not an IP
/// address, it is the socket peer.
String clientAddress(Request request, int trustedProxyHops) {
  final String? forwarded = forwardedAddress(
    request.headers['x-forwarded-for'],
    trustedProxyHops,
  );
  if (forwarded != null) return forwarded;
  final Object? info = request.context['shelf.io.connection_info'];
  return info is HttpConnectionInfo ? info.remoteAddress.address : 'unknown';
}

/// Open connections per address, an IPv6 address counted by its `/64`.
/// Entries leave at zero, so the map holds only addresses with a live
/// connection.
final class _AddressCounts {
  final Map<String, int> _counts = <String, int>{};

  int operator [](String address) => _counts[addressKey(address)] ?? 0;

  void add(String address) => _counts[addressKey(address)] = this[address] + 1;

  void remove(String address) {
    final int left = this[address] - 1;
    if (left <= 0) {
      _counts.remove(addressKey(address));
    } else {
      _counts[addressKey(address)] = left;
    }
  }
}

/// A WebSocket upgrade handler whose socket sits behind a [GuardedSocket], so
/// a message announcing more than `maxFrameBytes` closes the connection
/// before its payload is buffered. Origin checks happen before this. The
/// caller counts the connection first; [onRefused] undoes that when the
/// request is not a valid upgrade. [sockets] holds the WebSocket while it is
/// open, so [RelayServer.close] can say goodbye to it.
Handler _upgrade(
  RelayLimits limits,
  Set<WebSocket> sockets,
  void Function(WebSocketChannel channel) onConnection,
  void Function() onRefused,
) => (Request request) {
  final String? connection = request.headers['connection'];
  final String? key = request.headers['sec-websocket-key'];
  if (request.method != 'GET' ||
      request.headers['upgrade']?.toLowerCase() != 'websocket' ||
      connection == null ||
      !connection
          .toLowerCase()
          .split(',')
          .map((String token) => token.trim())
          .contains('upgrade')) {
    onRefused();
    return _json(404, 'WebSocket connections only');
  }
  if (request.headers['sec-websocket-version'] != '13' ||
      key == null ||
      request.protocolVersion != '1.1') {
    onRefused();
    return _json(400, 'not a WebSocket 13 upgrade');
  }
  request.hijack((StreamChannel<List<int>> channel) {
    final Socket socket = channel.sink as Socket;
    socket.add(
      utf8.encode(
        'HTTP/1.1 101 Switching Protocols\r\n'
        'Upgrade: websocket\r\n'
        'Connection: Upgrade\r\n'
        'Sec-WebSocket-Accept: ${WebSocketChannel.signKey(key)}\r\n\r\n',
      ),
    );
    final WebSocket webSocket = WebSocket.fromUpgradedSocket(
      GuardedSocket(socket, limits.maxFrameBytes),
      serverSide: true,
    )..pingInterval = limits.pingInterval;
    sockets.add(webSocket);
    webSocket.done
        .then<void>((Object? _) {}, onError: (Object error) {})
        .whenComplete(() => sockets.remove(webSocket));
    onConnection(IOWebSocketChannel(webSocket));
  });
};

/// Exact `scheme://host[:port]` origins, lower-cased. Throws [ArgumentError]
/// for anything else, including wildcards and paths.
Set<String> normalizeOrigins(Iterable<String> origins) {
  final Set<String> normalized = <String>{};
  for (final String raw in origins) {
    final String origin = raw.trim();
    if (origin.isEmpty) continue;
    final Uri? uri = Uri.tryParse(origin);
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        uri.host.contains('*') ||
        (uri.scheme != 'https' && uri.scheme != 'http') ||
        uri.userInfo.isNotEmpty ||
        (uri.path.isNotEmpty && uri.path != '/') ||
        uri.hasQuery ||
        uri.hasFragment) {
      throw ArgumentError.value(raw, 'allowedAppOrigins');
    }
    normalized.add('${uri.scheme}://${uri.authority}'.toLowerCase());
  }
  if (normalized.isEmpty) {
    throw ArgumentError.value(origins, 'allowedAppOrigins', 'is empty');
  }
  return Set<String>.unmodifiable(normalized);
}
