/// Browser storage behind the server profiles: a value this page could not
/// store lasts for the page only, and never outlives a removal or a reset
/// made in another tab.
library;

import 'dart:async';

import 'package:gloss_editor/services/server/storage_area.dart';
import 'package:test/test.dart';

/// One storage area shared by this tab and another one.
final class _Area implements StorageArea {
  final Map<String, String> values = <String, String>{};

  /// Every setItem throws, as a full quota does.
  bool full = false;

  /// Every call throws, as storage the browser disabled does.
  bool disabled = false;

  @override
  String? getItem(String key) {
    if (disabled) throw StateError('SecurityError');
    return values[key];
  }

  @override
  void setItem(String key, String value) {
    if (disabled || full) throw StateError('QuotaExceededError');
    values[key] = value;
  }

  @override
  void removeItem(String key) {
    if (disabled) throw StateError('SecurityError');
    values.remove(key);
  }
}

void main() {
  late _Area area;
  late StreamController<String?> otherTabs;
  late StorageAreaKeyValueStore store;

  setUp(() {
    area = _Area();
    otherTabs = StreamController<String?>.broadcast(sync: true);
    store = StorageAreaKeyValueStore(area, otherTabs: otherTabs.stream);
  });

  tearDown(() => otherTabs.close());

  /// Another tab removes [key] (null: clears everything) and says so.
  void otherTabRemoves(String? key) {
    if (key == null) {
      area.values.clear();
    } else {
      area.values.remove(key);
    }
    otherTabs.add(key);
  }

  test('should not bring back a key another tab removed', () {
    expect(store.write('gloss.token.a', 'token'), isTrue);
    otherTabRemoves('gloss.token.a');
    expect(store.read('gloss.token.a'), isNull);
    expect(store.write('gloss.servers', '[]'), isTrue);
    expect(store.read('gloss.token.a'), isNull);
  });

  test('should not bring back anything after another tab cleared storage', () {
    store.write('gloss.servers', '[{"id":"a"}]');
    store.write('gloss.token.a', 'token');
    otherTabRemoves(null);
    expect(store.read('gloss.servers'), isNull);
    expect(store.read('gloss.token.a'), isNull);
  });

  test('should keep a value the browser refused for this page and say so', () {
    area.full = true;
    expect(store.write('gloss.servers', '[{"id":"a"}]'), isFalse);
    expect(store.read('gloss.servers'), '[{"id":"a"}]');
  });

  test('should read the stored value again once the browser takes the key', () {
    area.full = true;
    store.write('gloss.servers', 'refused');
    area.full = false;
    expect(store.write('gloss.servers', 'stored'), isTrue);
    otherTabRemoves('gloss.servers');
    expect(store.read('gloss.servers'), isNull);
  });

  test('should drop a refused value another tab replaced', () {
    area.full = true;
    store.write('gloss.servers', 'mine');
    area.values['gloss.servers'] = 'theirs';
    otherTabs.add('gloss.servers');
    expect(store.read('gloss.servers'), 'theirs');
  });

  test('should drop a refused value on a clear in another tab', () {
    area.full = true;
    store.write('gloss.token.a', 'token');
    otherTabRemoves(null);
    expect(store.read('gloss.token.a'), isNull);
  });

  test('should forget a refused value on remove', () {
    area.full = true;
    store.write('gloss.token.a', 'token');
    store.remove('gloss.token.a');
    expect(store.read('gloss.token.a'), isNull);
  });

  test('should keep values for this page when storage is disabled', () {
    area.disabled = true;
    expect(store.write('gloss.servers', '[]'), isFalse);
    expect(store.read('gloss.servers'), '[]');
    store.remove('gloss.servers');
    expect(store.read('gloss.servers'), isNull);
    final StorageAreaKeyValueStore none = StorageAreaKeyValueStore(null);
    expect(none.write('gloss.servers', '[]'), isFalse);
    expect(none.read('gloss.servers'), '[]');
  });

  test('should tell listeners after the store forgot what another tab changed', () {
    area.full = true;
    store.write('gloss.token.a', 'token');
    final List<String?> heard = <String?>[];
    store.changes.listen((String? key) => heard.add(store.read('gloss.token.a')));
    otherTabRemoves('gloss.token.a');
    expect(heard, <String?>[null]);
  });
}
