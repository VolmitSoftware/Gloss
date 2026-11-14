import 'dart:math';

import 'kinds.dart';
import 'protocol.dart';

/// A paired browser's token. Only the SHA-256 of the token text is kept.
class FakeToken {
  FakeToken(this.id, this.hash, this.scopes, this.folders, this.label, this.admin, this.lifetime, this.client)
      : created = now(),
        expires = now().add(lifetime);

  final String id;
  final String hash;
  final List<String> scopes;

  /// The folders a `folders=` pairing limits it to; empty reaches all.
  final List<String> folders;
  final String? label;
  final String? admin;
  final Duration lifetime;
  final String? client;
  final DateTime created;

  /// Slides to now plus [lifetime] on every authenticated request and stream open.
  DateTime expires;
  DateTime? lastUsed;
  int counter = 0;

  bool has(String scope) => scopes.contains(scope);

  bool sees(String idOrPath) => reaches(folders, idOrPath);

  /// Refuses an entry outside the token's folders.
  void reach(String what, String idOrPath) {
    if (!sees(idOrPath)) throw folderDenied(what);
  }

  ApiError folderDenied(String what) =>
      ApiError('folder-denied', 'This token only reaches folders ${folders.join(', ')}: $what is outside them.', <String, Object?>{'folders': folders});

  Map<String, Object?> get state => <String, Object?>{
        'tokenId': id,
        'label': ?label,
        'scopes': scopes,
        if (folders.isNotEmpty) 'folders': folders,
        'expiresAt': instant(expires),
        'lifetimeSeconds': lifetime.inSeconds,
        'counter': counter,
        'client': ?client,
        'lastUsed': lastUsed == null ? null : instant(lastUsed!),
      };
}

/// A pairing link waiting for its browser: only the secret's hash is kept.
class WaitingLink {
  WaitingLink(this.tokenId, this.secretHash, this.expires, this.scopes, this.folders, this.label, this.admin, this.lifetime);

  final String tokenId;
  final String secretHash;
  DateTime expires;
  final List<String> scopes;
  final List<String> folders;
  final String? label;
  final String? admin;
  final Duration lifetime;
}

/// Pairing links, tokens, counters and the per-token request limits.
class FakeTokens {
  FakeTokens(this.requestsPerSecond, this.pairAttemptsPerMinute, this.ended);

  final int requestsPerSecond;

  /// Called when a token is revoked or expires, with the code its requests
  /// answer from then on; its event streams close with that reason.
  final void Function(String tokenId, String code) ended;
  final int pairAttemptsPerMinute;
  final Map<String, WaitingLink> _links = <String, WaitingLink>{};
  final Map<String, FakeToken> _tokens = <String, FakeToken>{};

  /// Revoked or expired ids, remembered a day with the code they answer.
  final Map<String, (String, DateTime)> _ended = <String, (String, DateTime)>{};
  final Map<String, (double, int)> _buckets = <String, (double, int)>{};
  final Map<String, int> _inFlight = <String, int>{};
  final Map<String, int> _pairAttempts = <String, int>{};
  final Stopwatch _clock = Stopwatch()..start();
  int _pairMinute = -1;

  List<FakeToken> get tokens => List<FakeToken>.unmodifiable(_tokens.values);

  FakeToken? token(String id) => _tokens[id];

  int get waitingLinks => _links.values.where((WaitingLink link) => now().isBefore(link.expires)).length;

  /// A one-time secret good for 10 minutes. Returns the link and the secret.
  (WaitingLink, String) pair(List<String> scopes, List<String> folders, String? label, String? admin, Duration lifetime) {
    if (waitingLinks >= maxWaitingLinks) throw invalid('Too many pairing links are waiting; let some expire or revoke them first');
    if (_tokens.length >= maxTokens) throw invalid('Token limit of $maxTokens reached; revoke unused tokens first');
    final String secret = randomText(32);
    final WaitingLink link = WaitingLink('tok_${randomText(12)}', sha256Text(secret), now().add(const Duration(minutes: 10)), scopes, folders, label, admin, lifetime);
    _links[link.tokenId] = link;
    return (link, secret);
  }

  /// Exchanges a link's secret once. Returns the token text and the token.
  (String, FakeToken) exchange(String tokenId, String secret, String? client) {
    final WaitingLink? link = _links[tokenId];
    if (link == null || !sameText(link.secretHash, sha256Text(secret))) {
      throw ApiError('pairing-invalid', 'This link was already used or is not a pairing link.');
    }
    if (!now().isBefore(link.expires)) throw ApiError('pairing-expired', 'This link expired.');
    _links.remove(tokenId);
    final String text = 'gls_$tokenId.${randomText(32)}';
    final FakeToken token = FakeToken(tokenId, sha256Text(text), link.scopes, link.folders, link.label, link.admin, link.lifetime, client);
    _tokens[tokenId] = token;
    return (text, token);
  }

  /// The token a bearer value names, its expiry slid forward; or why not.
  FakeToken authenticate(String? bearer) {
    final RegExpMatch? match = bearer == null ? null : RegExp(r'^gls_(tok_[A-Za-z0-9_-]{8,64})\.[A-Za-z0-9_-]{43}$').firstMatch(bearer);
    if (match == null) throw ApiError('unauthorized', 'A valid editor token is required.');
    final String id = match.group(1)!;
    final (String, DateTime)? gone = _ended[id];
    if (gone != null) {
      throw ApiError(gone.$1, gone.$1 == 'token-revoked' ? 'This token was revoked.' : 'This token expired; pair the browser again.');
    }
    final FakeToken? token = _tokens[id];
    if (token == null || !sameText(token.hash, sha256Text(bearer!))) throw ApiError('unauthorized', 'A valid editor token is required.');
    final DateTime time = now();
    if (!time.isBefore(token.expires)) {
      _end(id, 'token-expired');
      throw ApiError('token-expired', 'This token expired; pair the browser again.');
    }
    token.lastUsed = time;
    token.expires = time.add(token.lifetime);
    return token;
  }

  /// Accepts a counted request's `X-Gloss-Counter`, which must exceed every earlier one.
  void advance(FakeToken token, String? header) {
    final int? value = header == null ? null : int.tryParse(header.trim());
    if (value == null) throw invalid('X-Gloss-Counter must be an integer on this request.');
    if (value <= token.counter) {
      throw ApiError('counter-replayed', 'X-Gloss-Counter must exceed ${token.counter}.', <String, Object?>{'counter': token.counter});
    }
    token.counter = value;
  }

  /// Revokes tokens and cancels waiting links; `all` for every one. Returns the ids.
  List<String> revoke(String idOrAll) {
    final List<String> ids = <String>[
      for (final String id in <String>{..._tokens.keys, ..._links.keys})
        if (idOrAll == 'all' || id == idOrAll) id,
    ];
    for (final String id in ids) {
      _links.remove(id);
      if (_tokens.containsKey(id)) _end(id, 'token-revoked');
    }
    return ids;
  }

  /// Makes a token, or a waiting link, expire now. Returns whether one did.
  bool expireNow(String id) {
    final WaitingLink? link = _links[id];
    if (link != null) {
      link.expires = now().subtract(const Duration(seconds: 1));
      return true;
    }
    final FakeToken? token = _tokens[id];
    if (token == null) return false;
    token.expires = now().subtract(const Duration(seconds: 1));
    return true;
  }

  /// Ends tokens past their expiry and forgets old links and ended ids.
  void housekeeping() {
    final DateTime time = now();
    final List<String> expired = <String>[
      for (final FakeToken token in _tokens.values)
        if (!time.isBefore(token.expires)) token.id,
    ];
    for (final String id in expired) {
      _end(id, 'token-expired');
    }
    _links.removeWhere((String id, WaitingLink link) => time.isAfter(link.expires.add(const Duration(hours: 1))));
    _ended.removeWhere((String id, (String, DateTime) gone) => time.isAfter(gone.$2.add(const Duration(days: 1))));
  }

  /// A restart forgets links not opened yet.
  void forgetLinks() => _links.clear();

  void _end(String id, String code) {
    _tokens.remove(id);
    _inFlight.remove(id);
    _buckets.remove(id);
    _ended[id] = (code, now());
    ended(id, code);
  }

  /// Counts a pair attempt (or a failed stream authentication) from [address].
  void pairAttempt(String address) {
    final int millis = now().millisecondsSinceEpoch;
    if (millis ~/ 60000 != _pairMinute) {
      _pairMinute = millis ~/ 60000;
      _pairAttempts.clear();
    }
    final int attempts = (_pairAttempts[address] ?? 0) + 1;
    _pairAttempts[address] = attempts;
    if (attempts > pairAttemptsPerMinute) {
      throw ApiError('rate-limited', 'Too many pairing attempts from this address.', <String, Object?>{'retryAfterMillis': 60000 - millis % 60000});
    }
  }

  /// Takes one request from the token's bucket ([requestsPerSecond], bursts
  /// of twice that) and one of its in-flight slots until the returned
  /// release runs.
  void Function() admit(FakeToken token) {
    final double capacity = requestsPerSecond * 2.0;
    final int micros = _clock.elapsedMicroseconds;
    final (double, int) bucket = _buckets[token.id] ?? (capacity, micros);
    final double available = min(capacity, bucket.$1 + (micros - bucket.$2) / 1e6 * requestsPerSecond);
    if (available < 1) {
      _buckets[token.id] = (available, micros);
      throw ApiError('rate-limited', 'Too many requests for this token.', <String, Object?>{'retryAfterMillis': ((1 - available) / requestsPerSecond * 1000).ceil()});
    }
    final int running = _inFlight[token.id] ?? 0;
    if (running >= concurrentRequestsPerToken) {
      throw ApiError('rate-limited', 'At most $concurrentRequestsPerToken requests run at once per token.', <String, Object?>{'retryAfterMillis': 250});
    }
    _buckets[token.id] = (available - 1, micros);
    _inFlight[token.id] = running + 1;
    bool released = false;
    return () {
      if (released) return;
      released = true;
      final int left = (_inFlight[token.id] ?? 1) - 1;
      if (left > 0) {
        _inFlight[token.id] = left;
      } else {
        _inFlight.remove(token.id);
      }
    };
  }
}

/// `expires` of `/gloss editor pair`: a number with d, h or m, or (fake
/// only) a whole number of seconds.
Duration parseLifetime(Object? value) {
  if (value == null) return const Duration(days: 7);
  if (value is int && value >= 1) return Duration(seconds: value);
  final RegExpMatch? match = value is String ? RegExp(r'^([0-9]{1,6})([dhm])$').firstMatch(value) : null;
  if (match == null) throw invalid('expires takes a number with d, h or m, such as 7d, or a whole number of seconds');
  final int amount = int.parse(match.group(1)!);
  final Duration lifetime = switch (match.group(2)) {
    'd' => Duration(days: amount),
    'h' => Duration(hours: amount),
    _ => Duration(minutes: amount),
  };
  if (lifetime < const Duration(minutes: 1) || lifetime > const Duration(days: 365)) throw invalid('expires must be 1 minute to 365 days');
  return lifetime;
}
