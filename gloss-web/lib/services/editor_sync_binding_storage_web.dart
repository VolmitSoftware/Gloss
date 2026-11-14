library;

import 'package:web/web.dart' as web;

/// One server's binding record (its header, or a bucket of rows), shared by
/// every tab of this browser like the server's workspace it describes.
String _key(String name) => 'gloss.sync.$name';

String? readEditorSyncBinding(String name) {
  try {
    return web.window.localStorage.getItem(_key(name));
  } catch (_) {
    return null;
  }
}

bool writeEditorSyncBinding(String name, String value) {
  try {
    web.window.localStorage.setItem(_key(name), value);
    return true;
  } catch (_) {
    return false;
  }
}

void removeEditorSyncBinding(String name) {
  try {
    web.window.localStorage.removeItem(_key(name));
  } catch (_) {}
}
