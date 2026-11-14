import 'dart:convert';

import 'transport.dart';

/// De-duplicates events while the relay and direct streams both run.
///
/// Event frames carry no sequence number or cursor (schema `event`), so the
/// two copies of one event are matched by content: an event is delivered
/// from whichever stream brings it first, and the other stream's identical
/// copy is dropped when it arrives. Nothing is lost, because an event only
/// one stream carries (published before the direct stream was registered) is
/// delivered as it comes. The events are state notices (catalog versions,
/// diagnostics, preview states, applied changes), so dropping a genuinely
/// repeated identical one within the overlap changes nothing for listeners.
class EventOverlap {
  static const int _limit = 256;

  final Map<ConnectionPath, List<String>> _unmatched =
      <ConnectionPath, List<String>>{
        ConnectionPath.direct: <String>[],
        ConnectionPath.relay: <String>[],
      };

  /// Whether [event] from [from] is new to listeners.
  bool accept(ConnectionPath from, Map<String, Object?> event) {
    final String key = jsonEncode(_canonical(event));
    final ConnectionPath other = from == ConnectionPath.direct
        ? ConnectionPath.relay
        : ConnectionPath.direct;
    if (_unmatched[other]!.remove(key)) return false;
    final List<String> mine = _unmatched[from]!;
    mine.add(key);
    if (mine.length > _limit) mine.removeAt(0);
    return true;
  }

  /// [value] with object keys sorted, so key order does not matter.
  static Object? _canonical(Object? value) {
    if (value is Map<String, Object?>) {
      final List<String> keys = value.keys.toList()..sort();
      return <String, Object?>{
        for (final String key in keys) key: _canonical(value[key]),
      };
    }
    if (value is List<Object?>) {
      return <Object?>[for (final Object? item in value) _canonical(item)];
    }
    return value;
  }
}
