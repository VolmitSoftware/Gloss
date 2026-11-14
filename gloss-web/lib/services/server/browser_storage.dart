import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'storage_area.dart';

/// localStorage or sessionStorage of this browser.
class BrowserKeyValueStore extends StorageAreaKeyValueStore {
  /// Other tabs' writes to localStorage arrive as storage events.
  BrowserKeyValueStore.local()
    : super(
        _WebStorageArea.open(() => web.window.localStorage),
        otherTabs: _storageEvents(),
      );

  BrowserKeyValueStore.session()
    : super(_WebStorageArea.open(() => web.window.sessionStorage));

  static Stream<String?> _storageEvents() {
    final StreamController<String?> events =
        StreamController<String?>.broadcast(sync: true);
    web.window.addEventListener(
      'storage',
      ((web.StorageEvent event) => events.add(event.key)).toJS,
    );
    return events.stream;
  }
}

final class _WebStorageArea implements StorageArea {
  _WebStorageArea(this._storage);

  final web.Storage _storage;

  /// The area, or null when the browser refuses to open it.
  static _WebStorageArea? open(web.Storage Function() storage) {
    try {
      return _WebStorageArea(storage());
    } on Object {
      return null;
    }
  }

  @override
  String? getItem(String key) => _storage.getItem(key);

  @override
  void setItem(String key, String value) => _storage.setItem(key, value);

  @override
  void removeItem(String key) => _storage.removeItem(key);
}
