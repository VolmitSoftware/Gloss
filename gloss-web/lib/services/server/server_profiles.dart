/// Paired servers and their tokens in browser storage. Pure Dart over a
/// [KeyValueStore]: localStorage and sessionStorage in the browser, a map in
/// tests.
library;

import 'dart:async';
import 'dart:convert';

import 'pairing.dart';
import 'transport.dart';

/// String storage: localStorage and sessionStorage in the browser, a map in
/// tests.
abstract class KeyValueStore {
  String? read(String key);

  /// Stores [value]. False when the browser refused it, so it lasts only as
  /// long as this page.
  bool write(String key, String value);

  void remove(String key);

  /// Keys another tab changed (null: everything may have changed).
  Stream<String?> get changes;
}

class MemoryKeyValueStore implements KeyValueStore {
  MemoryKeyValueStore({this.refuseWrites = false});

  final Map<String, String> values = <String, String>{};

  /// Acts like a private window that refuses storage.
  final bool refuseWrites;
  final StreamController<String?> _changes =
      StreamController<String?>.broadcast(sync: true);

  @override
  String? read(String key) => values[key];

  @override
  bool write(String key, String value) {
    values[key] = value;
    return !refuseWrites;
  }

  @override
  void remove(String key) => values.remove(key);

  @override
  Stream<String?> get changes => _changes.stream;

  /// What another tab sharing this storage does: write [key] and say so.
  void writeFromOtherTab(String key, String? value) {
    if (value == null) {
      values.remove(key);
    } else {
      values[key] = value;
    }
    _changes.add(key);
  }
}

/// A token from `POST /v1/pair` (sync-relay/README.md, editor API section 4).
class ServerToken {
  const ServerToken({
    required this.token,
    required this.tokenId,
    required this.scopes,
    required this.expiresAt,
    this.folders,
    this.lifetime,
    this.admin,
    this.counter = 0,
  });

  final String token;
  final String tokenId;
  final List<String> scopes;

  /// The folders the token reaches; null for every folder.
  final List<String>? folders;

  /// When the pairing ends unless used; each use moves it.
  final DateTime expiresAt;

  /// How long the pairing lasts after its last use.
  final Duration? lifetime;

  /// The UUID of the player who made the link; null for the console.
  final String? admin;

  /// The last accepted `X-Gloss-Counter`.
  final int counter;

  bool has(String scope) => scopes.contains(scope);

  ServerToken copyWith({
    DateTime? expiresAt,
    List<String>? scopes,
    int? counter,
  }) => ServerToken(
    token: token,
    tokenId: tokenId,
    scopes: scopes ?? this.scopes,
    expiresAt: expiresAt ?? this.expiresAt,
    folders: folders,
    lifetime: lifetime,
    admin: admin,
    counter: counter ?? this.counter,
  );

  Map<String, Object?> toJson() => <String, Object?>{
    'token': token,
    'tokenId': tokenId,
    'scopes': scopes,
    'folders': ?folders,
    'expiresAt': expiresAt.toUtc().toIso8601String(),
    if (lifetime != null) 'lifetimeSeconds': lifetime!.inSeconds,
    'admin': ?admin,
    'counter': counter,
  };

  static ServerToken? fromJson(Object? json) {
    if (json is! Map<String, Object?> ||
        json['token'] is! String ||
        json['tokenId'] is! String) {
      return null;
    }
    final DateTime? expires = DateTime.tryParse('${json['expiresAt']}');
    if (expires == null) return null;
    final Object? lifetime = json['lifetimeSeconds'];
    final Object? counter = json['counter'];
    return ServerToken(
      token: json['token']! as String,
      tokenId: json['tokenId']! as String,
      scopes: <String>[
        for (final Object? scope
            in (json['scopes'] as List<Object?>?) ?? const <Object?>[])
          if (scope is String) scope,
      ],
      folders: json['folders'] is List<Object?>
          ? <String>[
              for (final Object? folder in json['folders']! as List<Object?>)
                '$folder',
            ]
          : null,
      expiresAt: expires.toUtc(),
      lifetime: lifetime is num ? Duration(seconds: lifetime.toInt()) : null,
      admin: json['admin'] is String ? json['admin']! as String : null,
      counter: counter is num ? counter.toInt() : 0,
    );
  }
}

/// A paired server: where to reach it and the pinned key. The token is kept
/// apart, in localStorage only when [remember] is set.
class ServerProfile {
  const ServerProfile({
    required this.fingerprint,
    required this.serverKey,
    required this.name,
    this.directUrl,
    this.relayUrl,
    this.label,
    this.remember = false,
    this.lastPath,
    this.documents,
    this.images,
  });

  final String fingerprint;
  final String serverKey;
  final String name;
  final String? directUrl;
  final String? relayUrl;

  /// The link name the pairing was made with (`label=`).
  final String? label;
  final bool remember;

  /// The path that last worked, tried first on the next connect.
  final ConnectionPath? lastPath;

  /// The server's document and image counts at the last catalog read; null
  /// before the server was opened.
  final int? documents;
  final int? images;

  String get id => serverIdOf(fingerprint);

  /// Paths in the order to try: the last good one first, then direct before
  /// the relay.
  List<(ConnectionPath, String)> get paths {
    final List<(ConnectionPath, String)> paths = <(ConnectionPath, String)>[
      if (directUrl != null) (ConnectionPath.direct, directUrl!),
      if (relayUrl != null) (ConnectionPath.relay, relayUrl!),
    ];
    if (lastPath == ConnectionPath.relay && paths.length == 2) {
      return paths.reversed.toList();
    }
    return paths;
  }

  ServerProfile copyWith({
    String? name,
    bool? remember,
    ConnectionPath? lastPath,
    int? documents,
    int? images,
  }) => ServerProfile(
    fingerprint: fingerprint,
    serverKey: serverKey,
    name: name ?? this.name,
    directUrl: directUrl,
    relayUrl: relayUrl,
    label: label,
    remember: remember ?? this.remember,
    lastPath: lastPath ?? this.lastPath,
    documents: documents ?? this.documents,
    images: images ?? this.images,
  );

  Map<String, Object?> toJson() => <String, Object?>{
    'fingerprint': fingerprint,
    'serverKey': serverKey,
    'name': name,
    'directUrl': ?directUrl,
    'relayUrl': ?relayUrl,
    'label': ?label,
    'remember': remember,
    if (lastPath != null) 'lastPath': lastPath!.name,
    'documents': ?documents,
    'images': ?images,
  };

  static ServerProfile? fromJson(Object? json) {
    if (json is! Map<String, Object?> ||
        json['fingerprint'] is! String ||
        json['serverKey'] is! String) {
      return null;
    }
    final String fingerprint = json['fingerprint']! as String;
    // The pin is the point: a stored profile whose key does not hash to its
    // fingerprint was tampered with and is dropped.
    try {
      if (!RegExp(r'^sha256:[0-9a-f]{64}$').hasMatch(fingerprint) ||
          fingerprintOf(json['serverKey']! as String) != fingerprint) {
        return null;
      }
    } on FormatException {
      return null;
    }
    final Object? documents = json['documents'];
    final Object? images = json['images'];
    return ServerProfile(
      fingerprint: fingerprint,
      serverKey: json['serverKey']! as String,
      name: json['name'] is String
          ? json['name']! as String
          : serverIdOf(fingerprint),
      directUrl: json['directUrl'] as String?,
      relayUrl: json['relayUrl'] as String?,
      label: json['label'] as String?,
      remember: json['remember'] == true,
      lastPath: ConnectionPath.values.asNameMap()[json['lastPath']],
      documents: documents is num ? documents.toInt() : null,
      images: images is num ? images.toInt() : null,
    );
  }
}

/// Saved servers in localStorage; each token in localStorage when the user
/// asked to be remembered, otherwise in sessionStorage for this tab.
class ServerProfileStore {
  ServerProfileStore({required this.local, required this.session});

  final KeyValueStore local;
  final KeyValueStore session;

  static const String profilesKey = 'gloss.servers';
  static String tokenKey(String serverId) => 'gloss.token.$serverId';

  /// What the last tab to learn it found: the server online or not
  /// reachable. Every tab reads it, so all of them say the same word.
  static String reachKey(String serverId) => 'gloss.reach.$serverId';
  static const String _reachPrefix = 'gloss.reach.';

  List<ServerProfile> load() {
    final String? text = local.read(profilesKey);
    if (text == null) return const <ServerProfile>[];
    try {
      final Object? json = jsonDecode(text);
      if (json is! List<Object?>) return const <ServerProfile>[];
      return <ServerProfile>[
        for (final Object? entry in json)
          if (ServerProfile.fromJson(entry) case final ServerProfile profile)
            profile,
      ];
    } on FormatException {
      return const <ServerProfile>[];
    }
  }

  /// False when the browser refused to store the list.
  bool save(List<ServerProfile> profiles) => local.write(
    profilesKey,
    jsonEncode(<Map<String, Object?>>[
      for (final ServerProfile profile in profiles) profile.toJson(),
    ]),
  );

  /// Another tab changed the saved servers.
  Stream<void> get changes =>
      local.changes.where((String? key) => key == null || key == profilesKey);

  /// Another tab changed a server's token (paired again, or forgot it).
  Stream<String?> get tokenChanges => local.changes.where(
    (String? key) => key == null || key.startsWith('gloss.token.'),
  );

  /// Another tab learned whether a server is online: its id.
  Stream<String> get reachChanges => local.changes
      .where((String? key) => key != null && key.startsWith(_reachPrefix))
      .map((String? key) => key!.substring(_reachPrefix.length));

  /// Whether [serverId] was last found online; null when no tab said.
  bool? reach(String serverId) {
    final String? text = local.read(reachKey(serverId));
    if (text == null) return null;
    try {
      final Object? json = jsonDecode(text);
      return json is Map<String, Object?> && json['online'] is bool
          ? json['online']! as bool
          : null;
    } on FormatException {
      return null;
    }
  }

  /// Tells every tab whether [serverId] answers; [at] makes each report a
  /// change other tabs hear.
  void saveReach(
    String serverId, {
    required bool online,
    required DateTime at,
  }) => local.write(
    reachKey(serverId),
    jsonEncode(<String, Object?>{
      'online': online,
      'at': at.millisecondsSinceEpoch,
    }),
  );

  ServerToken? token(ServerProfile profile) {
    final String? text =
        session.read(tokenKey(profile.id)) ??
        (profile.remember ? local.read(tokenKey(profile.id)) : null);
    if (text == null) return null;
    try {
      return ServerToken.fromJson(jsonDecode(text));
    } on FormatException {
      return null;
    }
  }

  /// False when the browser refused to keep the token, so it lasts only
  /// until this page closes.
  bool saveToken(ServerProfile profile, ServerToken token) {
    final String text = jsonEncode(token.toJson());
    final bool kept = session.write(tokenKey(profile.id), text);
    if (profile.remember) {
      return local.write(tokenKey(profile.id), text) && kept;
    }
    local.remove(tokenKey(profile.id));
    return kept;
  }

  void forgetToken(ServerProfile profile) {
    session.remove(tokenKey(profile.id));
    local.remove(tokenKey(profile.id));
  }
}
