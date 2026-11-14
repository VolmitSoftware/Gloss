import 'package:gloss_editor/components/server/pair_copy.dart';
import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/services/server/server_connection.dart';
import 'package:test/test.dart';

const String httpsPage = 'https://gloss.volmitsoftware.com';
const String localDirect = 'http://127.0.0.1:9799';

const PairProblem blocked = PairProblem(
  'The browser blocked the connection to your server.',
  'Allow local network access for this site in the browser\'s site settings, then press Pair again.',
);

const PairProblem silent = PairProblem(
  'The server did not answer.',
  'Check that it is running, then press Pair again.',
);

void expectProblem(PairProblem actual, PairProblem expected) {
  expect(actual.title, expected.title);
  expect(actual.next, expected.next);
  expect(actual.newLink, expected.newLink);
}

PairProblem problem(String code, {String pageOrigin = httpsPage, String? directUrl, ErrorCopy? copy}) =>
    pairProblemOf(code, pageOrigin: pageOrigin, directUrl: directUrl, copy: copy);

void main() {
  test('should offer a new link for a used or expired link', () {
    expectProblem(problem('pairing-invalid'), notALink);
    expect(notALink.title, 'This link was already used or is not a pairing link.');
    expect(notALink.next, 'Run /gloss editor pair again and paste the new link into New link.');
    expect(notALink.newLink, isTrue);
    expectProblem(problem('pairing-expired'), expiredLink);
    expect(expiredLink.title, 'This link expired.');
    expect(expiredLink.newLink, isTrue);
  });

  test('should say an expired link once, with its time', () {
    final PairProblem expired = expiredLinkAt(DateTime(2026, 11, 13, 13, 35));
    expect(expired.title, 'Link expired at 13:35.');
    expect(expired.next, 'Run /gloss editor pair again and paste the new link into New link.');
    expect(expired.newLink, isTrue);
    // The card's own line names the time only while the link still works.
    expect(pairExpiryLine(DateTime(2026, 11, 13, 13, 35), spent: true), isNull);
    expect(
      pairExpiryLine(DateTime(2026, 11, 13, 13, 35), spent: false),
      'Link works until 13:35, once.',
    );
  });

  test('should count the pairing limit down', () {
    expect(pairingLimit(const Duration(milliseconds: 40500)).next, 'Try again in 41 s.');
    expect(pairingLimit(const Duration(seconds: 12)).next, 'Try again in 12 s.');
    expect(pairingLimit(const Duration(milliseconds: 200)).next, 'Try again in 1 s.');
    expect(pairingLimit(const Duration(seconds: 12)).wait, const Duration(seconds: 12));
  });

  test('should state the real wait of the pairing limit', () {
    final PairProblem limited = pairProblemOf(
      'rate-limited',
      pageOrigin: httpsPage,
      retryAfter: const Duration(milliseconds: 40500),
    );
    expect(limited.title, 'Too many pairing attempts.');
    expect(limited.next, 'Try again in 41 s.');
    expect(limited.wait, const Duration(milliseconds: 40500));
    expect(problem('rate-limited').next, isNot(contains('few seconds')));
  });

  test('should name this page\'s origin when the server refuses it', () {
    final PairProblem refused = problem('origin-not-allowed', pageOrigin: 'http://localhost:8800');
    expect(refused.title, 'The server does not accept this editor\'s address.');
    expect(refused.next, 'Add http://localhost:8800 to allowedOrigins under [editor] in gloss.toml, then press Pair again.');
    expect(refused.newLink, isFalse);
  });

  test('should word each case no server answered', () {
    expectProblem(problem('local-network-blocked'), blocked);
    expectProblem(
      problem('relay-unavailable'),
      const PairProblem('The server is not connected to the relay.', 'Check that it is running, then press Pair again.'),
    );
    expectProblem(
      problem('relay-down'),
      const PairProblem(
        'The relay does not answer.',
        'Check relayUrl under [editor] in gloss.toml, or pair over the direct listener.',
      ),
    );
    expectProblem(problem('unreachable', directUrl: localDirect), blocked);
    expectProblem(problem('unreachable', pageOrigin: 'http://localhost:8800', directUrl: localDirect), silent);
    expectProblem(problem('unreachable', directUrl: 'https://play.example.net:9799'), silent);
    expectProblem(problem('unreachable'), silent);
    expectProblem(problem('timeout', directUrl: 'http://192.168.1.5:9799'), blocked);
    expectProblem(problem('relay-unreachable', pageOrigin: 'http://localhost:8800'), silent);
  });

  test('should ask for a link from the right server when another one answered', () {
    final PairProblem wrong = problem('wrong-server');
    expect(wrong.title, 'A different server answered.');
    expect(wrong.newLink, isTrue);
  });

  test('should word any other refusal by the server\'s own copy', () {
    expectProblem(
      problem('busy'),
      const PairProblem('The server is busy.', 'Try again in a moment.'),
    );
    expectProblem(problem('no-path'), PairProblem('This link has no server address.', notALink.next, newLink: true));
    final ErrorCopy scope = serverCopy('invalid-request', error: <String, Object?>{'message': 'Bad client'});
    expectProblem(problem('invalid-request', copy: scope), PairProblem(scope.cause, scope.next));
    expectProblem(
      problem('something-new'),
      const PairProblem('The server refused the link.', 'Press Pair again, or make a new link.'),
    );
  });

  test('should adapt a refusal from pairing', () {
    expectProblem(
      pairProblemFor(PairingRefused.because(PairingFailure.unreachable), pageOrigin: httpsPage, directUrl: localDirect),
      blocked,
    );
    final ErrorCopy internal = serverCopy('internal');
    expectProblem(
      pairProblemFor(PairingRefused('internal', internal), pageOrigin: httpsPage),
      PairProblem(internal.cause, internal.next),
    );
  });

  test('should never use an em dash', () {
    for (final String code in <String>[
      for (final PairingFailure failure in PairingFailure.values) failure.code,
      'no-path',
      'busy',
      'timeout',
    ]) {
      expect(problem(code, directUrl: localDirect).toString(), isNot(contains('—')), reason: code);
    }
  });

  test('should name this browser from its user agent', () {
    const Map<String, String> agents = <String, String>{
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36':
          'Chrome on macOS',
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0':
          'Edge on Windows',
      'Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0': 'Firefox on Linux',
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1':
          'Safari on iOS',
      'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36':
          'Chrome on Android',
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 OPR/115.0.0.0':
          'Opera on Windows',
      'Chrome/131.0.0.0': 'Chrome',
      'curl/8.7.1': 'Web editor',
    };
    agents.forEach((String agent, String name) => expect(browserName(agent), name, reason: agent));
  });
}
