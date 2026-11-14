library;

import 'server_hub.dart';
import 'server_profiles.dart';
import 'transport.dart';

ServerHub createBrowserHub({
  Future<void> Function(String serverId)? onForget,
}) => ServerHub(
  profiles: ServerProfileStore(
    local: MemoryKeyValueStore(),
    session: MemoryKeyValueStore(),
  ),
  open: (ConnectionPath path, String url, String fingerprint) async =>
      throw TransportFailure(path, 'unreachable'),
  onForget: onForget,
);

String browserUserAgent() => '';

String browserOrigin() => 'http://127.0.0.1';

void listenBrowserWake(void Function() wake) {}
