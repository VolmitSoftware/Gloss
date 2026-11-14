/// Plain words for every way a server, the relay or the browser's own
/// connection can refuse or fail: what happened and what to do next, each one
/// sentence (sync-relay/README.md, editor API section 13). Every message the
/// editor shows for a server problem comes from here, so a code never
/// reaches the screen bare.
library;

import '../../l10n/hui_localizations.dart';
import 'scope_words.dart';

/// What happened and the next action, each one sentence. [code] is the
/// error, transport or closing code it words.
class ErrorCopy {
  const ErrorCopy(this.code, this.cause, this.next);

  final String code;
  final String cause;
  final String next;

  /// Both sentences, for a toast, an alert or a status line.
  String get text => next.isEmpty ? cause : '$cause $next';

  @override
  bool operator ==(Object other) =>
      other is ErrorCopy &&
      other.code == code &&
      other.cause == cause &&
      other.next == next;

  @override
  int get hashCode => Object.hash(code, cause, next);

  @override
  String toString() => text;
}

/// The command that makes a pairing link.
const String pairCommand = '/gloss editor pair';

String get _pairAgain => huiText(
  'Run {command} and paste the new link into New link.',
  <String, Object?>{'command': pairCommand},
);

String get _reconnecting => huiText('Reconnecting.');

/// Error codes after which the token is no use.
const Set<String> tokenErrorCodes = <String>{
  'unauthorized',
  'token-expired',
  'token-revoked',
};

/// Every server `error.code` of the contract. [origin] is this page's origin
/// for `origin-not-allowed`; [error] is the whole error object, for the
/// scope, folders and message it may carry; [relay] says the request went
/// over the relay.
ErrorCopy? knownServerCopy(
  String? code, {
  String? origin,
  Map<String, Object?>? error,
  bool relay = false,
}) {
  final Object? message = error?['message'];
  switch (code) {
    case 'pairing-invalid':
      return ErrorCopy(
        code!,
        huiText('This link was already used or is not a pairing link.'),
        _pairAgain,
      );
    case 'pairing-expired':
      return ErrorCopy(code!, huiText('This link expired.'), _pairAgain);
    case 'origin-not-allowed':
      return originCopy(origin);
    case 'unauthorized' || 'token-expired' || 'token-revoked':
      return ErrorCopy(code!, huiText('Pairing ended.'), _pairAgain);
    case 'scope-missing':
      final Object? scope = error?['scope'];
      return ErrorCopy(
        code!,
        scope is String
            ? huiText('This pairing cannot {action}.', <String, Object?>{
                'action': scopeWord(scope).toLowerCase(),
              })
            : huiText('This pairing does not allow that.'),
        huiText(
          'Run {command} for a new link that allows it.',
          <String, Object?>{'command': pairCommand},
        ),
      );
    case 'folder-denied':
      final Object? folders = error?['folders'];
      return ErrorCopy(
        code!,
        folders is List<Object?> && folders.isNotEmpty
            ? huiText(
                'This pairing only reaches folders {folders}.',
                <String, Object?>{'folders': folders.join(', ')},
              )
            : huiText('This pairing does not reach that folder.'),
        huiText('Run {command} with the folders you need.', <String, Object?>{
          'command': pairCommand,
        }),
      );
    case 'counter-replayed':
      return ErrorCopy(
        code!,
        huiText('The server saw an older request again.'),
        huiText('Try again.'),
      );
    case 'confirm-deletes':
      return ErrorCopy(
        code!,
        huiText('Nothing was published.'),
        huiText('Publish again and confirm the deletions.'),
      );
    case 'too-large':
      return ErrorCopy(
        code!,
        relay
            ? huiText('This is too large to send over the relay.')
            : huiText('This is too large to send.'),
        huiText('Connect directly, or publish fewer documents at once.'),
      );
    case 'rate-limited' || 'busy':
      return ErrorCopy(
        code!,
        huiText('The server is busy.'),
        huiText('Try again in a moment.'),
      );
    case 'timeout':
      return ErrorCopy(
        code!,
        huiText('The server did not answer in time.'),
        huiText('Check its status, then try again.'),
      );
    case 'invalid-request' when error?['missing'] is List<Object?>:
      // A publish sent again after the server restarted or its uploads
      // expired: a new publish uploads the images again.
      return ErrorCopy(
        code!,
        huiText('The server no longer holds the images this publish uploaded.'),
        huiText('Publish again to send them.'),
      );
    case 'invalid-request':
      return ErrorCopy(
        code!,
        message is String && message.isNotEmpty
            ? huiText(
                'The server refused the request: {message}.',
                <String, Object?>{'message': _trimStop(message)},
              )
            : huiText('The server refused the request.'),
        huiText('Reload the editor.'),
      );
    case 'unsupported-protocol':
      return ErrorCopy(
        code!,
        huiText('This server runs a different Gloss version.'),
        huiText('Update Gloss or reload the editor.'),
      );
    case 'not-found':
      return ErrorCopy(
        code!,
        huiText('That is no longer on the server.'),
        huiText('Sync to read the server again.'),
      );
    case 'server-offline':
      return ErrorCopy(
        code!,
        huiText('The server is not connected to the relay.'),
        huiText(
          'Check that the server is running; it connects to the relay at its first pairing.',
        ),
      );
    case 'internal':
      return ErrorCopy(
        code!,
        huiText('The server could not complete this; see its log.'),
        '',
      );
  }
  return null;
}

/// The server answered and refused this page's [origin]: the config key to
/// change, not a server to restart.
ErrorCopy originCopy(String? origin) => ErrorCopy(
  'origin-not-allowed',
  huiText('The server does not accept this editor\'s address.'),
  huiText(
    'Add {origin} to allowedOrigins under [editor] in gloss.toml.',
    <String, Object?>{'origin': origin ?? huiText('this page\'s address')},
  ),
);

String _trimStop(String message) =>
    message.endsWith('.') ? message.substring(0, message.length - 1) : message;

/// The code an answer without an error body stands for.
String codeForStatus(int status) => switch (status) {
  401 => 'unauthorized',
  403 => 'scope-missing',
  404 => 'not-found',
  409 => 'counter-replayed',
  428 => 'confirm-deletes',
  410 => 'pairing-expired',
  413 => 'too-large',
  429 => 'rate-limited',
  503 => 'busy',
  504 => 'timeout',
  >= 500 => 'internal',
  _ => 'invalid-request',
};

/// Copy for a server `error.code`, or for [status] when the answer had none.
ErrorCopy serverCopy(
  String? code, {
  int? status,
  String? origin,
  Map<String, Object?>? error,
  bool relay = false,
}) =>
    knownServerCopy(code, origin: origin, error: error, relay: relay) ??
    (status == null
        ? _unknown(code)
        : knownServerCopy(
                codeForStatus(status),
                origin: origin,
                error: error,
                relay: relay,
              ) ??
              _unknown(code));

ErrorCopy _unknown(String? code) => ErrorCopy(
  code ?? 'refused',
  huiText('The server refused the request.'),
  huiText('Try again, or check the server console.'),
);

/// The relay's own error codes.
ErrorCopy? knownRelayCopy(String? code) => switch (code) {
  'invalid-frame' => ErrorCopy(
    code!,
    huiText('The relay could not read a message.'),
    huiText('Reload the editor.'),
  ),
  'too-large' => ErrorCopy(
    code!,
    huiText('This is too large to send over the relay.'),
    huiText('Connect directly, or publish fewer documents at once.'),
  ),
  'rate-limited' => ErrorCopy(
    code!,
    huiText('The relay is rate limiting this browser.'),
    huiText('Retrying.'),
  ),
  'handshake-failed' => ErrorCopy(
    code!,
    huiText('The server failed the relay handshake.'),
    huiText('Check that it is running.'),
  ),
  'replaced' => ErrorCopy(
    code!,
    huiText('Another tab took over this connection.'),
    huiText('Close other editor tabs for this server.'),
  ),
  'capacity' => ErrorCopy(
    code!,
    huiText('The relay is full.'),
    huiText('Retrying.'),
  ),
  'queue-full' => ErrorCopy(
    code!,
    huiText('Too many requests are waiting at the relay.'),
    huiText('Try again in a moment.'),
  ),
  _ => null,
};

ErrorCopy relayCopy(String? code) =>
    knownRelayCopy(code) ??
    ErrorCopy(
      code ?? 'relay',
      huiText('The relay refused the connection.'),
      _reconnecting,
    );

/// Why the browser's own path failed (a transport failure code). [origin]
/// is this page's origin, for `origin-not-allowed`.
ErrorCopy? knownTransportCopy(String code, {String? origin}) => switch (code) {
  'origin-not-allowed' => originCopy(origin),
  'unreachable' || 'no-answer' => ErrorCopy(
    code,
    huiText('The server did not answer.'),
    huiText('Check that it is running.'),
  ),
  'timeout' => ErrorCopy(
    code,
    huiText('The server did not answer in time.'),
    huiText('Retrying.'),
  ),
  'dropped' => ErrorCopy(code, huiText('Connection dropped.'), _reconnecting),
  'closed' => ErrorCopy(
    code,
    huiText('Connection closed.'),
    huiText('Open the server again from the start page.'),
  ),
  'stream-closed' => ErrorCopy(
    code,
    huiText('Live updates stopped.'),
    _reconnecting,
  ),
  'server-offline' => ErrorCopy(
    code,
    huiText('The server is not connected to the relay.'),
    huiText(
      'Check that the server is running; it connects to the relay at its first pairing.',
    ),
  ),
  'server-left' ||
  'restarting' => ErrorCopy(code, huiText('Server restarting.'), _reconnecting),
  'relay-unreachable' => ErrorCopy(
    code,
    huiText('The relay did not answer.'),
    huiText('Check your network.'),
  ),
  'wrong-server' => ErrorCopy(
    code,
    huiText('A different server answered.'),
    huiText('Check the link is from the right server, then pair again.'),
  ),
  'no-token' => ErrorCopy(
    code,
    huiText('This tab has no pairing for this server.'),
    _pairAgain,
  ),
  _ => null,
};

ErrorCopy transportCopy(String code, {String? origin}) =>
    knownTransportCopy(code, origin: origin) ??
    knownRelayCopy(code) ??
    knownTransportCopy('dropped')!;

/// Why the server closed the event stream (the `closing` event's reason).
ErrorCopy? knownClosingCopy(String? reason) => switch (reason) {
  'token-revoked' ||
  'token-expired' => ErrorCopy(reason!, huiText('Pairing ended.'), _pairAgain),
  'server-stopping' => ErrorCopy(
    reason!,
    huiText('Server restarting.'),
    _reconnecting,
  ),
  'limit' => ErrorCopy(
    reason!,
    huiText('Fell behind on server events.'),
    _reconnecting,
  ),
  _ => null,
};

/// The closing reasons the contract names.
const Set<String> closingReasons = <String>{
  'token-revoked',
  'token-expired',
  'server-stopping',
  'limit',
};

ErrorCopy closingCopyOf(String? reason) =>
    knownClosingCopy(reason) ??
    ErrorCopy(
      reason ?? 'closed',
      huiText('The server closed the connection.'),
      _reconnecting,
    );

/// Why pairing gave no token, in the cases the pairing card words apart.
enum PairingFailure {
  /// The link ran out before it was used.
  expired('pairing-expired'),

  /// The server does not know the link: used, revoked or never made there.
  used('pairing-invalid'),

  /// The server refuses requests from this page's address.
  originNotAllowed('origin-not-allowed'),

  /// No path answered.
  unreachable('unreachable'),

  /// The browser held or refused this https page's call to the server's
  /// plain http address on the local network.
  localNetworkBlocked('local-network-blocked'),

  /// The relay refused, or the server is not on it.
  relayUnavailable('relay-unavailable'),

  /// The relay itself does not answer.
  relayUnreachable('relay-down'),

  /// A server with another key answered, so the secret was not sent.
  wrongServer('wrong-server'),

  /// The server refused for another reason, worded by its own code.
  other('refused');

  const PairingFailure(this.code);

  /// The refusal code this failure is reported with.
  final String code;

  /// The failure a refusal [code] stands for: [other] for any code not listed.
  static PairingFailure of(String code) => values.firstWhere(
    (PairingFailure failure) => failure.code == code,
    orElse: () => other,
  );
}
