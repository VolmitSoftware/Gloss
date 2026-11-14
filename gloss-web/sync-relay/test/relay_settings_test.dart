import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';

void main() {
  test('should default to the hosted editor origin and port 8080', () {
    final RelaySettings settings = RelaySettings.fromEnvironment(
      <String, String>{},
    );
    expect(settings.port, 8080);
    expect(settings.address, '0.0.0.0');
    expect(settings.allowedAppOrigins, <String>[
      'https://gloss.volmitsoftware.com',
      'https://gloss-editor.web.app',
      'https://gloss-editor.firebaseapp.com',
    ]);
    expect(settings.limits.maxFrameBytes, 1024 * 1024);
    expect(settings.limits.requestTimeout, const Duration(seconds: 10));
    expect(settings.trustedProxyHops, 1, reason: 'Cloud Run adds one hop');
  });

  test('should read exact origins and limits from the environment', () {
    final RelaySettings settings = RelaySettings.fromEnvironment(
      <String, String>{
        'PORT': '9000',
        'ALLOWED_APP_ORIGINS':
            'https://Gloss-Editor.web.app, http://127.0.0.1:8098',
        'MAX_PENDING_PER_APP': '8',
        'REQUEST_TIMEOUT_SECONDS': '20',
        'MAX_SERVER_CONNECTIONS_PER_ADDRESS': '3',
        'MAX_APP_CONNECTIONS_PER_ADDRESS': '5',
        'MAX_APPS_PER_ADDRESS_PER_SERVER': '2',
        'MAX_APPS_SIGNING_IN_PER_SERVER': '12',
        'MAX_APPS_SIGNING_IN_PER_ADDRESS_PER_SERVER': '3',
        'SIGN_IN_TIMEOUT_SECONDS': '45',
        'TRUSTED_PROXY_HOPS': '0',
      },
    );
    expect(settings.trustedProxyHops, 0);
    expect(settings.port, 9000);
    expect(settings.allowedAppOrigins, <String>[
      'https://gloss-editor.web.app',
      'http://127.0.0.1:8098',
    ]);
    expect(settings.limits.maxPendingPerApp, 8);
    expect(settings.limits.requestTimeout, const Duration(seconds: 20));
    expect(settings.limits.maxAgentConnectionsPerAddress, 3);
    expect(settings.limits.maxAppConnectionsPerAddress, 5);
    expect(settings.limits.maxAppsPerAddressPerServer, 2);
    expect(settings.limits.maxAppsSigningInPerServer, 12);
    expect(settings.limits.maxAppsSigningInPerAddressPerServer, 3);
    expect(settings.limits.signInTimeout, const Duration(seconds: 45));
  });

  test('should refuse wildcard, path and malformed origins', () {
    for (final String origin in <String>[
      '*',
      'https://*.web.app',
      'https://a.example/path',
      'ftp://a.example',
      'a.example',
    ]) {
      expect(
        () => RelaySettings.fromEnvironment(<String, String>{
          'ALLOWED_APP_ORIGINS': origin,
        }),
        throwsArgumentError,
        reason: origin,
      );
    }
  });

  test('should refuse limits outside their ranges', () {
    expect(
      () => RelaySettings.fromEnvironment(<String, String>{
        'MAX_FRAME_BYTES': '10',
      }),
      throwsFormatException,
    );
    expect(
      () => RelaySettings.fromEnvironment(<String, String>{'PORT': 'x'}),
      throwsFormatException,
    );
  });
}
