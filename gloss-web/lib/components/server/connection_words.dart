/// A server connection's state in two or three words, and its badge tone.
/// Pure Dart.
library;

import '../../l10n/hui_localizations.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/transport.dart';
import '../../ui/tone.dart';

/// The state of a saved server on the start page: Online, Checking or Not
/// reachable (from a reachability check or the live connection), Pairing
/// ended, or Not paired in this tab.
(String, BadgeTone) serverState(
  ConnectionStatus status, {
  required bool paired,
}) {
  if (status.phase == ConnectionPhase.needsPairing) {
    return (huiText('Pairing ended'), BadgeTone.error);
  }
  if (!paired) return (huiText('Not paired here'), BadgeTone.neutral);
  return switch (status.phase) {
    ConnectionPhase.online => (huiText('Online'), BadgeTone.ok),
    // A retry after a failed attempt is still not reachable; only a first
    // attempt is checking, so the word never alternates.
    ConnectionPhase.connecting when status.attempt > 0 => (
      huiText('Not reachable'),
      BadgeTone.warning,
    ),
    ConnectionPhase.connecting => (huiText('Checking'), BadgeTone.info),
    // Waiting out a retry: the server did not answer the last check.
    ConnectionPhase.reconnecting => (
      huiText('Not reachable'),
      BadgeTone.warning,
    ),
    ConnectionPhase.refused => (huiText('Refused'), BadgeTone.error),
    ConnectionPhase.needsPairing => (huiText('Pairing ended'), BadgeTone.error),
    ConnectionPhase.offline =>
      status.probed
          ? (huiText('Not reachable'), BadgeTone.warning)
          : (huiText('Checking'), BadgeTone.info),
  };
}

/// "Online (direct)" or "Online (relay)", for Advanced.
String pathLabel(ConnectionPath path) => switch (path) {
  ConnectionPath.direct => huiText('Online (direct)'),
  ConnectionPath.relay => huiText('Online (relay)'),
};

/// Documents and images in words, such as "1,575 documents, 200 images";
/// "Not opened yet" before the first read.
String countsLine({int? documents, int? images}) {
  if (documents == null) return huiText('Not opened yet');
  final String docs = huiPlural(
    'start.documents',
    documents,
    oneEnglish: '{count} document',
    otherEnglish: '{count} documents',
  );
  if (images == null || images == 0) return docs;
  final String pictures = huiPlural(
    'start.images',
    images,
    oneEnglish: '{count} image',
    otherEnglish: '{count} images',
  );
  return '$docs, $pictures';
}
