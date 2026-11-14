/// The start page's Continue list, worked out from plain facts. Pure Dart.
library;

import '../../l10n/hui_localizations.dart';
import '../../services/server/server_profiles.dart';
import 'connection_words.dart';

/// The browser workspace as the start page lists it.
final class LocalSummary {
  const LocalSummary({required this.documents, required this.hasWork});

  final int documents;

  /// More than what a fresh browser starts with (the sample menu).
  final bool hasWork;
}

/// One row of Continue: a paired server, or the browser workspace.
final class ContinueRow {
  const ContinueRow({
    required this.key,
    required this.name,
    required this.line,
    this.server,
    this.warning,
  });

  final String key;
  final String name;

  /// Counts and where it lives, in words.
  final String line;
  final ServerProfile? server;

  /// "Pairing ends in n days unless used.", within three days of the end.
  final String? warning;

  bool get isServer => server != null;
}

/// Each paired server once, by name, then the browser workspace when it
/// holds work. [tokenOf] is the token this tab holds for a server.
List<ContinueRow> continueRows({
  required List<ServerProfile> servers,
  required LocalSummary local,
  required ServerToken? Function(String serverId) tokenOf,
  required DateTime now,
}) => <ContinueRow>[
  for (final ServerProfile server in servers)
    ContinueRow(
      key: 'server-${server.id}',
      name: server.name,
      line: countsLine(documents: server.documents, images: server.images),
      server: server,
      warning: endsSoon(tokenOf(server.id), now),
    ),
  if (local.hasWork)
    ContinueRow(
      key: 'local',
      name: huiText('Browser workspace'),
      line:
          '${countsLine(documents: local.documents)}, ${huiText('in this browser')}',
    ),
];

/// "Pairing ends in n days unless used." when [token] ends within three
/// days of [now]; else null.
String? endsSoon(ServerToken? token, DateTime now) {
  if (token == null) return null;
  final Duration left = token.expiresAt.difference(now.toUtc());
  if (left > const Duration(days: 3)) return null;
  final int days = left.isNegative ? 0 : (left.inHours / 24).ceil();
  return huiPlural(
    'sync.pairing_ends',
    days,
    oneEnglish: 'Pairing ends in {count} day unless used.',
    otherEnglish: 'Pairing ends in {count} days unless used.',
  );
}
