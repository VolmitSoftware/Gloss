/// What the pairing screens say when a link cannot be used: one plain cause,
/// the one thing to do next, and whether a new link is needed
/// (sync-relay/README.md, editor API section 13). Pure Dart.
library;

import '../../l10n/hui_localizations.dart';
import '../../services/server/error_copy.dart';
import '../../services/server/local_network.dart';
import '../../services/server/server_connection.dart';

class PairProblem {
  const PairProblem(this.title, this.next, {this.newLink = false, this.wait});

  /// What happened, one sentence.
  final String title;

  /// The one thing to do next, one sentence.
  final String next;

  /// The link cannot work again: offer the field for a new one.
  final bool newLink;

  /// How long Pair stays off: the wait the pairing limit named.
  final Duration? wait;

  @override
  String toString() => '$title $next';
}

String get _newLinkStep => huiText(
  'Run {command} again and paste the new link into New link.',
  <String, Object?>{'command': pairCommand},
);

/// A pasted or opened link that is not a pairing link.
PairProblem get notALink => PairProblem(
  huiText('This link was already used or is not a pairing link.'),
  _newLinkStep,
  newLink: true,
);

/// A link the server called expired.
PairProblem get expiredLink =>
    PairProblem(huiText('This link expired.'), _newLinkStep, newLink: true);

/// A link whose ten minutes ran out at [at], found before anything is sent.
PairProblem expiredLinkAt(DateTime at) => PairProblem(
  huiText('Link expired at {time}.', <String, Object?>{'time': clockTime(at)}),
  _newLinkStep,
  newLink: true,
);

/// The card's line under the server name while the link still works; null
/// once it cannot ([spent]): the problem then says why, once.
String? pairExpiryLine(DateTime expiresAt, {required bool spent}) => spent
    ? null
    : huiText('Link works until {time}, once.', <String, Object?>{
        'time': clockTime(expiresAt),
      });

/// [at] as local HH:MM.
String clockTime(DateTime at) {
  final DateTime local = at.toLocal();
  return '${local.hour.toString().padLeft(2, '0')}:'
      '${local.minute.toString().padLeft(2, '0')}';
}

/// The pairing limit with [left] of its wait to go; the card counts it
/// down and keeps Pair off until it ends.
PairProblem pairingLimit(Duration left) => PairProblem(
  huiText('Too many pairing attempts.'),
  huiText('Try again in {seconds} s.', <String, Object?>{
    'seconds': (left.inMilliseconds / 1000).ceil(),
  }),
  wait: left,
);

/// How this browser is listed on the server, from its user agent: "Chrome on
/// macOS", or "Web editor" when it cannot tell.
String browserName(String userAgent) {
  final String browser = switch (userAgent) {
    final String ua when ua.contains('Edg/') => 'Edge',
    final String ua when ua.contains('OPR/') => 'Opera',
    final String ua when ua.contains('Firefox/') => 'Firefox',
    final String ua when ua.contains('Chrome/') => 'Chrome',
    final String ua when ua.contains('Safari/') => 'Safari',
    _ => '',
  };
  final String system = switch (userAgent) {
    final String ua when ua.contains('iPhone') || ua.contains('iPad') => 'iOS',
    final String ua when ua.contains('Android') => 'Android',
    final String ua when ua.contains('Mac OS X') || ua.contains('Macintosh') =>
      'macOS',
    final String ua when ua.contains('Windows') => 'Windows',
    final String ua when ua.contains('Linux') => 'Linux',
    _ => '',
  };
  if (browser.isEmpty) return huiText('Web editor');
  return system.isEmpty
      ? browser
      : huiText('{browser} on {system}', <String, Object?>{
          'browser': browser,
          'system': system,
        });
}

/// The one adapter from a refused pairing to what the card says. [pageOrigin]
/// is this page's origin, [directUrl] the address the link names.
PairProblem pairProblemFor(
  PairingRefused refused, {
  required String pageOrigin,
  String? directUrl,
}) => pairProblemOf(
  refused.code,
  pageOrigin: pageOrigin,
  directUrl: directUrl,
  copy: refused.copy,
  retryAfter: refused.retryAfter,
);

PairProblem get _blocked => PairProblem(
  huiText('The browser blocked the connection to your server.'),
  huiText(
    'Allow local network access for this site in the browser\'s site settings, then press Pair again.',
  ),
);

PairProblem get _silent => PairProblem(
  huiText('The server did not answer.'),
  huiText('Check that it is running, then press Pair again.'),
);

/// The problem for a refusal [code]: a server error code, the browser's own
/// (such as `unreachable`) or a [PairingFailure] code. [retryAfter] is the
/// wait a `rate-limited` answer named.
PairProblem pairProblemOf(
  String code, {
  required String pageOrigin,
  String? directUrl,
  ErrorCopy? copy,
  Duration? retryAfter,
}) => switch (PairingFailure.of(code)) {
  PairingFailure.used => notALink,
  PairingFailure.expired => expiredLink,
  PairingFailure.originNotAllowed => PairProblem(
    huiText('The server does not accept this editor\'s address.'),
    huiText(
      'Add {origin} to allowedOrigins under [editor] in gloss.toml, then press Pair again.',
      <String, Object?>{'origin': pageOrigin},
    ),
  ),
  PairingFailure.localNetworkBlocked => _blocked,
  PairingFailure.relayUnavailable => PairProblem(
    huiText('The server is not connected to the relay.'),
    huiText('Check that it is running, then press Pair again.'),
  ),
  PairingFailure.relayUnreachable => PairProblem(
    huiText('The relay does not answer.'),
    huiText(
      'Check relayUrl under [editor] in gloss.toml, or pair over the direct listener.',
    ),
  ),
  PairingFailure.unreachable => _unanswered(pageOrigin, directUrl),
  PairingFailure.wrongServer => PairProblem(
    huiText('A different server answered.'),
    huiText('Check the link is from the right server, then pair again.'),
    newLink: true,
  ),
  PairingFailure.other => _untyped(
    code,
    pageOrigin: pageOrigin,
    directUrl: directUrl,
    copy: copy,
    retryAfter: retryAfter,
  ),
};

/// No answer: a page served over https reaching a server on this machine or
/// the local network meets the browser's local network permission first.
PairProblem _unanswered(String pageOrigin, String? directUrl) =>
    pageOrigin.startsWith('https://') &&
        directUrl != null &&
        isLocalNetworkHost(Uri.tryParse(directUrl)?.host ?? '')
    ? _blocked
    : _silent;

/// A refusal no [PairingFailure] covers, worded by its own code.
PairProblem _untyped(
  String code, {
  required String pageOrigin,
  String? directUrl,
  ErrorCopy? copy,
  Duration? retryAfter,
}) => switch (code) {
  'no-path' => PairProblem(
    huiText('This link has no server address.'),
    _newLinkStep,
    newLink: true,
  ),
  'no-answer' ||
  'timeout' ||
  'relay-unreachable' ||
  'server-offline' ||
  'dropped' ||
  'closed' => _unanswered(pageOrigin, directUrl),
  // The pairing limit counts attempts per clock minute and says what is
  // left of it.
  'rate-limited' when retryAfter != null => pairingLimit(retryAfter),
  'rate-limited' || 'busy' => PairProblem(
    huiText('The server is busy.'),
    huiText('Try again in a moment.'),
  ),
  _ =>
    copy != null && copy.cause.isNotEmpty
        ? PairProblem(copy.cause, copy.next)
        : PairProblem(
            huiText('The server refused the link.'),
            huiText('Press Pair again, or make a new link.'),
          ),
};
