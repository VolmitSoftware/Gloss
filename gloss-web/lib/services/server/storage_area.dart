/// localStorage or sessionStorage behind [KeyValueStore], in pure Dart over
/// a [StorageArea]: the browser's in `browser_storage.dart`, a map in tests.
library;

import 'dart:async';

import 'server_profiles.dart';

/// The calls a browser storage area answers. Each may throw: storage that is
/// disabled refuses every call, a full quota refuses [setItem].
abstract interface class StorageArea {
  String? getItem(String key);

  void setItem(String key, String value);

  void removeItem(String key);
}

/// A value the browser refuses (a private window, a full quota) is kept for
/// this page only, and [write] says so. It lasts until the browser stores
/// that key, the page removes it, or another tab changes or clears it.
class StorageAreaKeyValueStore implements KeyValueStore {
  /// [otherTabs] names the keys another tab changed (null: it cleared all).
  StorageAreaKeyValueStore(this._area, {Stream<String?>? otherTabs}) {
    otherTabs?.listen((String? key) {
      if (key == null) {
        _refused.clear();
      } else {
        _refused.remove(key);
      }
      _changes.add(key);
    });
  }

  final StorageArea? _area;

  /// Values the browser would not store, by key.
  final Map<String, String> _refused = <String, String>{};
  final StreamController<String?> _changes =
      StreamController<String?>.broadcast(sync: true);

  @override
  Stream<String?> get changes => _changes.stream;

  @override
  String? read(String key) {
    final String? refused = _refused[key];
    if (refused != null) return refused;
    try {
      return _area?.getItem(key);
    } on Object {
      return null;
    }
  }

  @override
  bool write(String key, String value) {
    final StorageArea? area = _area;
    if (area != null) {
      try {
        area.setItem(key, value);
        _refused.remove(key);
        return true;
      } on Object {
        // Kept in memory below.
      }
    }
    _refused[key] = value;
    return false;
  }

  @override
  void remove(String key) {
    _refused.remove(key);
    try {
      _area?.removeItem(key);
    } on Object {
      // Nothing stored.
    }
  }
}
