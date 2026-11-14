/// A ranked top-N list the plugin computes itself (`leaderboards/<id>.json`,
/// `LeaderboardDoc.java`): where the number comes from, how many entries are
/// kept, which way they sort and when the period rolls over.
///
/// The model keeps the whole JSON object and writes single keys, so fields
/// this editor shows no control for (a statistic's material or entity, keys a
/// newer plugin adds) survive every edit.
library;

import 'dart:convert';

import 'gloss_doc.dart';
import 'json_codec.dart';

const List<String> glossLeaderboardSourceTypes = <String>[
  'statistic',
  'papi',
  'metric',
];
const List<String> glossLeaderboardOrders = <String>['desc', 'asc'];
const List<String> glossLeaderboardResets = <String>[
  'never',
  'hourly',
  'daily',
  'weekly',
  'monthly',
];

/// `LeaderboardDoc.MAX_SIZE` and `MAX_PLAYERS_PER_TICK`.
const int glossLeaderboardMaxSize = 1000;
const int glossLeaderboardMaxPlayersPerTick = 64;

bool looksLikeLeaderboardDoc(Object? raw) =>
    raw is Map && raw['schemaVersion'] is num && raw['source'] is Map;

GlossLeaderboardDoc decodeGlossLeaderboardDoc(String source) =>
    GlossLeaderboardDoc.fromJson(jsonDecode(source));

String encodeGlossLeaderboardDoc(GlossLeaderboardDoc doc) =>
    huiWriteJson(doc.toJson());

final class GlossLeaderboardDoc extends GlossDoc {
  GlossLeaderboardDoc({
    super.schemaVersion = 1,
    super.revision = glossInitialRevision,
    Map<String, Object?>? data,
  }) : data =
           data ??
           <String, Object?>{
             'source': <String, Object?>{
               'type': 'statistic',
               'statistic': 'PLAY_ONE_MINUTE',
             },
             'size': 10,
             'order': 'desc',
             'reset': 'never',
             'includeOffline': true,
             'format': <String, Object?>{
               'value': '{{ value }}',
               'name': '{{ name }}',
             },
           };

  /// Every key of the document except `schemaVersion` and `revision`.
  final Map<String, Object?> data;

  static GlossLeaderboardDoc fromJson(Object? raw) {
    final Map<String, dynamic> map = huiReadObject(raw, r'$');
    final int schemaVersion = glossReadSchemaVersion(map, 'leaderboards');
    if (map['source'] is! Map) {
      throw const HuiFormatException(
        'leaderboard documents require a source block',
        r'$.source',
      );
    }
    return GlossLeaderboardDoc(
      schemaVersion: schemaVersion,
      revision: glossReadRevision(map),
      data: huiDeepCopyMap(<String, Object?>{
        for (final MapEntry<String, dynamic> entry in map.entries)
          if (entry.key != 'schemaVersion' && entry.key != 'revision')
            entry.key: entry.value,
      }),
    );
  }

  Map<String, Object?> _block(String key) {
    final Object? value = data[key];
    if (value is Map<String, Object?>) return value;
    final Map<String, Object?> block = value is Map
        ? value.cast<String, Object?>()
        : <String, Object?>{};
    data[key] = block;
    return block;
  }

  String _text(Map<String, Object?> block, String key, String fallback) {
    final Object? value = block[key];
    return value is String && value.trim().isNotEmpty ? value : fallback;
  }

  String get sourceType => _text(_block('source'), 'type', 'statistic');

  set sourceType(String value) => _block('source')['type'] = value;

  /// The source's own key: `statistic`, `placeholder` or `key`.
  String get sourceKey => switch (sourceType.toLowerCase()) {
    'papi' || 'placeholder' => 'placeholder',
    'metric' => 'key',
    _ => 'statistic',
  };

  String get sourceValue => _text(_block('source'), sourceKey, '');

  set sourceValue(String value) => _block('source')[sourceKey] = value;

  int get size {
    final Object? value = data['size'];
    return value is num ? value.toInt().clamp(1, glossLeaderboardMaxSize) : 10;
  }

  set size(int value) => data['size'] = value;

  String get order => _text(data, 'order', 'desc').toLowerCase();

  set order(String value) => data['order'] = value;

  String get reset => _text(data, 'reset', 'never').toLowerCase();

  set reset(String value) => data['reset'] = value;

  bool get includeOffline => data['includeOffline'] != false;

  set includeOffline(bool value) => data['includeOffline'] = value;

  String get valueFormat => _text(_block('format'), 'value', '{{ value }}');

  set valueFormat(String value) => _block('format')['value'] = value;

  String get nameFormat => _text(_block('format'), 'name', '{{ name }}');

  set nameFormat(String value) => _block('format')['name'] = value;

  int get playersPerTick {
    final Object? value = _block('sample')['playersPerTick'];
    return value is num
        ? value.toInt().clamp(1, glossLeaderboardMaxPlayersPerTick)
        : 8;
  }

  set playersPerTick(int value) => _block('sample')['playersPerTick'] = value;

  @override
  Map<String, Object?> toJson() => <String, Object?>{
    'schemaVersion': schemaVersion,
    'revision': revision,
    ...huiDeepCopyMap(data),
  };
}
