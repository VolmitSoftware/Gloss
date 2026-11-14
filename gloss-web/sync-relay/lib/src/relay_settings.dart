import 'package:gloss_sync_relay/src/relay_limits.dart';
import 'package:gloss_sync_relay/src/relay_server.dart';

/// Process settings read from environment variables (see README).
final class RelaySettings {
  final int port;
  final String address;
  final List<String> allowedAppOrigins;
  final RelayLimits limits;

  /// Proxies in front of the relay that append to `X-Forwarded-For`: 1 behind
  /// Cloud Run, 0 when browsers connect to the relay directly.
  final int trustedProxyHops;

  const RelaySettings({
    required this.port,
    required this.address,
    required this.allowedAppOrigins,
    required this.limits,
    required this.trustedProxyHops,
  });

  /// Throws [FormatException] or [ArgumentError] naming the bad variable.
  factory RelaySettings.fromEnvironment(Map<String, String> environment) {
    int integer(String name, int fallback, int minimum, int maximum) {
      final String? raw = environment[name]?.trim();
      if (raw == null || raw.isEmpty) return fallback;
      final int? value = int.tryParse(raw);
      if (value == null || value < minimum || value > maximum) {
        throw FormatException(
          '$name must be an integer from $minimum through $maximum',
        );
      }
      return value;
    }

    Duration seconds(String name, Duration fallback) =>
        Duration(seconds: integer(name, fallback.inSeconds, 1, 3600));

    const RelayLimits defaults = RelayLimits();
    final RelayLimits limits = RelayLimits(
      maxFrameBytes: integer(
        'MAX_FRAME_BYTES',
        defaults.maxFrameBytes,
        1024,
        16 * 1024 * 1024,
      ),
      maxAppMessagesPerWindow: integer(
        'MAX_APP_MESSAGES_PER_SECOND',
        defaults.maxAppMessagesPerWindow,
        1,
        10000,
      ),
      maxAgentMessagesPerWindow: integer(
        'MAX_AGENT_MESSAGES_PER_SECOND',
        defaults.maxAgentMessagesPerWindow,
        1,
        100000,
      ),
      maxPendingPerApp: integer(
        'MAX_PENDING_PER_APP',
        defaults.maxPendingPerApp,
        1,
        4096,
      ),
      maxStreamsPerApp: integer(
        'MAX_STREAMS_PER_APP',
        defaults.maxStreamsPerApp,
        1,
        64,
      ),
      maxPendingGlobal: integer(
        'MAX_PENDING_GLOBAL',
        defaults.maxPendingGlobal,
        1,
        100000,
      ),
      maxAppsPerServer: integer(
        'MAX_APPS_PER_SERVER',
        defaults.maxAppsPerServer,
        1,
        4096,
      ),
      maxAppsPerAddressPerServer: integer(
        'MAX_APPS_PER_ADDRESS_PER_SERVER',
        defaults.maxAppsPerAddressPerServer,
        1,
        4096,
      ),
      maxAppsSigningInPerServer: integer(
        'MAX_APPS_SIGNING_IN_PER_SERVER',
        defaults.maxAppsSigningInPerServer,
        1,
        4096,
      ),
      maxAppsSigningInPerAddressPerServer: integer(
        'MAX_APPS_SIGNING_IN_PER_ADDRESS_PER_SERVER',
        defaults.maxAppsSigningInPerAddressPerServer,
        1,
        4096,
      ),
      maxAgentConnectionsPerAddress: integer(
        'MAX_SERVER_CONNECTIONS_PER_ADDRESS',
        defaults.maxAgentConnectionsPerAddress,
        1,
        100000,
      ),
      maxAppConnectionsPerAddress: integer(
        'MAX_APP_CONNECTIONS_PER_ADDRESS',
        defaults.maxAppConnectionsPerAddress,
        1,
        100000,
      ),
      maxAgents: integer('MAX_SERVERS', defaults.maxAgents, 1, 100000),
      maxApps: integer('MAX_APPS', defaults.maxApps, 1, 100000),
      handshakeTimeout: seconds(
        'HANDSHAKE_TIMEOUT_SECONDS',
        defaults.handshakeTimeout,
      ),
      subscriptionTimeout: seconds(
        'SUBSCRIPTION_TIMEOUT_SECONDS',
        defaults.subscriptionTimeout,
      ),
      signInTimeout: seconds('SIGN_IN_TIMEOUT_SECONDS', defaults.signInTimeout),
      requestTimeout: seconds(
        'REQUEST_TIMEOUT_SECONDS',
        defaults.requestTimeout,
      ),
      pingInterval: seconds('PING_INTERVAL_SECONDS', defaults.pingInterval),
    );
    limits.validate();
    final String? rawOrigins = environment['ALLOWED_APP_ORIGINS']?.trim();
    final List<String> origins = rawOrigins == null || rawOrigins.isEmpty
        ? defaultAllowedAppOrigins
        : rawOrigins
              .split(',')
              .map((String origin) => origin.trim())
              .where((String origin) => origin.isNotEmpty)
              .toList();
    final List<String> normalized = normalizeOrigins(origins).toList();
    final String? address = environment['BIND_ADDRESS']?.trim();
    return RelaySettings(
      port: integer('PORT', 8080, 1, 65535),
      address: address == null || address.isEmpty ? '0.0.0.0' : address,
      allowedAppOrigins: List<String>.unmodifiable(normalized),
      limits: limits,
      trustedProxyHops: integer('TRUSTED_PROXY_HOPS', 1, 0, 8),
    );
  }
}
