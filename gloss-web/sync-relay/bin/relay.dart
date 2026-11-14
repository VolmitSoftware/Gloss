import 'dart:async';
import 'dart:io';

import 'package:gloss_sync_relay/gloss_sync_relay.dart';

Future<void> main(List<String> args) async {
  final Map<String, String> environment = Platform.environment;
  final RelaySettings settings;
  try {
    settings = RelaySettings.fromEnvironment(environment);
  } on Object catch (error) {
    stderr.writeln('gloss-relay: $error');
    exitCode = 64;
    return;
  }
  final RelayServer relay = await startRelayServer(
    port: settings.port,
    address: settings.address,
    limits: settings.limits,
    allowedAppOrigins: settings.allowedAppOrigins,
    trustedProxyHops: settings.trustedProxyHops,
  );
  stdout.writeln(
    'gloss-relay listening on ${relay.address.host}:${relay.port} '
    'origins=${settings.allowedAppOrigins.join(',')}',
  );
  final Completer<void> shutdown = Completer<void>();
  void stop(ProcessSignal signal) {
    if (!shutdown.isCompleted) shutdown.complete();
  }

  final StreamSubscription<ProcessSignal> terminate = ProcessSignal.sigterm
      .watch()
      .listen(stop);
  final StreamSubscription<ProcessSignal> interrupt = ProcessSignal.sigint
      .watch()
      .listen(stop);
  await shutdown.future;
  await terminate.cancel();
  await interrupt.cancel();
  stdout.writeln('gloss-relay stopping');
  await relay.close();
  await stdout.flush();
  // Ping timers and broker timeouts would keep the isolate alive; every socket
  // has been told by now, so the process ends here.
  exit(0);
}
