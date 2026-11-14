/// The text a document was read from, with the editor's edits written into
/// it key by key.
///
/// The models encode every field, defaults included, while a file (a server
/// copy, an import, a preset document) holds only what its author set. An
/// edit therefore lands in the authored text as the keys it changed: keys the
/// file left out stay out, and a document edited back to what it was is the
/// authored bytes again, so it matches its server base.
library;

import 'dart:convert';

import '../model/json_codec.dart';

final class AuthoredSource {
  /// [canonical] is the model's encoding of [source]. With [inherits] the
  /// text is a preset document: a key the edit removed is written as null so
  /// it overrides the inherited value. [modelText] re-encodes a candidate the
  /// way [canonical] was made; a candidate that does not read back as the
  /// edited model is not used.
  AuthoredSource(
    this.source,
    String canonical, {
    this.inherits = false,
    String Function(String json)? modelText,
  }) : _baselineText = canonical,
       _authored = _object(jsonDecode(source)),
       _baseline = _object(jsonDecode(canonical)),
       _readBack = modelText;

  final String source;
  final bool inherits;
  final String _baselineText;
  final Map<String, Object?> _authored;
  final Map<String, Object?> _baseline;
  final String Function(String json)? _readBack;
  String? _lastCanonical;
  String? _lastText;

  /// The authored text for the model encoded as [canonical].
  String encode(String canonical) {
    if (canonical == _baselineText) return source;
    if (canonical == _lastCanonical) return _lastText!;
    final String text = _encode(canonical);
    _lastCanonical = canonical;
    _lastText = text;
    return text;
  }

  /// Rounds of writing out keys the model works out by itself before the
  /// sparse form is given up.
  static const int _pinRounds = 6;

  String _encode(String canonical) {
    final Object? decoded = jsonDecode(canonical);
    if (decoded is! Map<String, Object?>) return canonical;
    if (_equal(decoded, _baseline)) return source;
    final Map<String, Object?> fine = _editMap(
      _authored,
      _baseline,
      decoded,
      fine: true,
      inherits: inherits,
    );
    if (_readBack == null) return _text(fine);
    final String fineText = _text(fine);
    if (_equal(_read(fineText), decoded)) return fineText;
    // What the edited model reads as once written out: [canonical] itself,
    // unless the model tidies its own output on a second read.
    final Object? target = _read(canonical);
    if (target is Map<String, Object?>) {
      final String? sparse = _settle(fine, target);
      if (sparse != null) return sparse;
      final String coarse = _text(
        _editMap(
          _authored,
          _baseline,
          decoded,
          fine: false,
          inherits: inherits,
        ),
      );
      if (_equal(_read(coarse), target)) return coarse;
    }
    return inherits ? _text(fine) : canonical;
  }

  /// [edited] as text once it reads back as [target], key order aside. A
  /// key the model works out from others (a style object's own defaults, one
  /// size from another) can read back differently while the file leaves it
  /// out; each such key is written with the edited model's value and the
  /// text is read again.
  String? _settle(Map<String, Object?> edited, Map<String, Object?> target) {
    Map<String, Object?> candidate = edited;
    for (int round = 0; round <= _pinRounds; round++) {
      final String text = _text(candidate);
      final Object? read = _read(text);
      if (_equal(read, target)) return text;
      if (read is! Map<String, Object?> || round == _pinRounds) return null;
      final Map<String, Object?>? pinned = _pin(candidate, read, target);
      if (pinned == null) return null;
      candidate = pinned;
    }
    return null;
  }

  String _text(Map<String, Object?> edited) =>
      _equal(edited, _authored) ? source : huiWriteJson(edited);

  /// [candidate] read into the model and encoded again, as a JSON tree, or
  /// null when it does not read.
  Object? _read(String candidate) {
    try {
      return jsonDecode(_readBack!(candidate));
    } catch (_) {
      return null;
    }
  }

  /// [candidate] with every key where [read] differs from [target] set to
  /// the [target] value, following objects and same-length lists; null when
  /// nothing changes.
  static Map<String, Object?>? _pin(
    Map<String, Object?> candidate,
    Map<String, Object?> read,
    Map<String, Object?> target,
  ) {
    Map<String, Object?>? result;
    for (final MapEntry<String, Object?> entry in target.entries) {
      final Object? want = entry.value;
      final Object? got = read[entry.key];
      if (read.containsKey(entry.key) && _equal(got, want)) continue;
      final Object? have = candidate[entry.key];
      final Object? next = _pinValue(have, got, want);
      if (candidate.containsKey(entry.key) && _equal(next, have)) continue;
      (result ??= Map<String, Object?>.of(candidate))[entry.key] = next;
    }
    return result;
  }

  static Object? _pinValue(Object? have, Object? got, Object? want) {
    if (have is Map<String, Object?> &&
        got is Map<String, Object?> &&
        want is Map<String, Object?>) {
      return _pin(have, got, want) ?? want;
    }
    if (have is List<Object?> &&
        got is List<Object?> &&
        want is List<Object?> &&
        have.length == want.length &&
        got.length == want.length) {
      return <Object?>[
        for (int index = 0; index < want.length; index++)
          _equal(got[index], want[index])
              ? have[index]
              : _pinValue(have[index], got[index], want[index]),
      ];
    }
    return want;
  }

  /// [authored] with the keys that differ between [before] and [after] set
  /// to their [after] values. [fine] follows changes into objects the file
  /// left out and into list items; otherwise those take [after] whole.
  static Map<String, Object?> _editMap(
    Map<String, Object?>? authored,
    Map<String, Object?> before,
    Map<String, Object?> after, {
    required bool fine,
    required bool inherits,
  }) {
    final Map<String, Object?> result = authored == null
        ? <String, Object?>{}
        : Map<String, Object?>.of(authored);
    for (final String key in <String>{...before.keys, ...after.keys}) {
      final bool had = before.containsKey(key);
      final bool has = after.containsKey(key);
      if (had == has && _equal(before[key], after[key])) continue;
      if (!has) {
        if (inherits) {
          result[key] = null;
        } else {
          result.remove(key);
        }
        continue;
      }
      result[key] = _editValue(
        authored != null && authored.containsKey(key),
        authored?[key],
        before[key],
        after[key],
        fine: fine,
        inherits: inherits,
      );
    }
    return result;
  }

  static Object? _editValue(
    bool present,
    Object? authored,
    Object? before,
    Object? after, {
    required bool fine,
    required bool inherits,
  }) {
    if (before is Map<String, Object?> && after is Map<String, Object?>) {
      if (authored is Map<String, Object?>) {
        return _editMap(
          authored,
          before,
          after,
          fine: fine,
          inherits: inherits,
        );
      }
      if (!present && (fine || inherits)) {
        return _editMap(null, before, after, fine: fine, inherits: inherits);
      }
      return after;
    }
    if (fine &&
        before is List<Object?> &&
        after is List<Object?> &&
        authored is List<Object?> &&
        authored.length == before.length) {
      return _editList(authored, before, after);
    }
    return after;
  }

  /// Items the edit left alone keep their authored form; a changed item at
  /// the same place is edited key by key. Presets replace lists whole, so
  /// items never inherit.
  static List<Object?> _editList(
    List<Object?> authored,
    List<Object?> before,
    List<Object?> after,
  ) {
    if (before.length == after.length) {
      return <Object?>[
        for (int index = 0; index < after.length; index++)
          _equal(before[index], after[index])
              ? authored[index]
              : _editValue(
                  true,
                  authored[index],
                  before[index],
                  after[index],
                  fine: true,
                  inherits: false,
                ),
      ];
    }
    final List<bool> used = List<bool>.filled(before.length, false);
    final List<Object?> result = <Object?>[];
    for (final Object? item in after) {
      int match = -1;
      for (int index = 0; index < before.length; index++) {
        if (!used[index] && _equal(before[index], item)) {
          match = index;
          break;
        }
      }
      if (match < 0) {
        result.add(item);
      } else {
        used[match] = true;
        result.add(authored[match]);
      }
    }
    return result;
  }

  static Map<String, Object?> _object(Object? value) {
    if (value is! Map<String, Object?>) {
      throw const FormatException('A document must be an object.');
    }
    return value;
  }

  static bool _equal(Object? left, Object? right) {
    if (identical(left, right)) return true;
    if (left is Map<String, Object?> && right is Map<String, Object?>) {
      return left.length == right.length &&
          left.entries.every(
            (MapEntry<String, Object?> entry) =>
                right.containsKey(entry.key) &&
                _equal(entry.value, right[entry.key]),
          );
    }
    if (left is List<Object?> && right is List<Object?>) {
      if (left.length != right.length) return false;
      for (int index = 0; index < left.length; index++) {
        if (!_equal(left[index], right[index])) return false;
      }
      return true;
    }
    return left == right;
  }
}
