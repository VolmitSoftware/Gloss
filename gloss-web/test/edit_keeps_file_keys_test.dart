/// An edit writes the keys it changed and nothing else.
///
/// A server file leaves out what it does not set, while the editor's models
/// encode every field. For every kind, each fixture is opened as written and
/// as its sparse form (every key whose value the model would supply anyway
/// left out), then one field at a time is edited through the store:
/// - the saved file differs from the opened one at that key only;
/// - typing the old value back, or Undo, saves the opened bytes exactly, so
///   the document matches its server base again.
library;

import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

/// Leaves edited per opened file, spread over the whole document.
const int _leavesPerFile = 24;

/// Keys an edit never targets: identity and the document envelope.
const Set<String> _fixedKeys = <String>{
  'schemaVersion',
  'revision',
  'id',
  'uuid',
  'type',
  'preset',
};

EditorStore _open(String name, String source) {
  final Map<String, String> storage = <String, String>{};
  final EditorStore store = EditorStore(
    workspace: Workspace(
      read: (String key) => storage[key],
      write: (String key, String value) {
        storage[key] = value;
        return true;
      },
    ),
    autosaveDelay: const Duration(days: 1),
  );
  addTearDown(store.dispose);
  expect(store.importJsonAsNewDocument(name, source), isTrue, reason: name);
  return store;
}

DocumentTypeAdapter _typeOf(EditorStore store) =>
    DocumentTypeRegistry.of(store.docKind);

/// The model's own text for [json]: every field, defaults included.
String _modelText(DocumentTypeAdapter type, String json) {
  if (type is GlossDocumentTypeAdapter) {
    return type.encodeDoc(type.decodeDoc(json));
  }
  if (identical(type, DocumentTypes.menu)) {
    return encodeHuiMenu(decodeHuiMenu(json));
  }
  if (identical(type, DocumentTypes.containerPreview)) {
    return encodeHuiPreviewDoc(decodeHuiPreviewDoc(json));
  }
  throw StateError('no codec for ${type.kind}');
}

/// Installs the model read from [json] as one edit, the way an inspector
/// field's onChanged replaces what it holds.
void _replace(EditorStore store, DocumentTypeAdapter type, String json) {
  if (type is GlossDocumentTypeAdapter) {
    store.replaceGlossDoc('edit', type.decodeDoc(json));
  } else if (identical(type, DocumentTypes.menu)) {
    store.replaceMenu('edit', decodeHuiMenu(json));
  } else {
    store.replacePreviewDoc('edit', decodeHuiPreviewDoc(json));
  }
}

String? _modelTextOrNull(DocumentTypeAdapter type, String json) {
  try {
    return _modelText(type, json);
  } catch (_) {
    return null;
  }
}

/// [source] without every key the model fills in by itself: the file a
/// server writes when it leaves defaults out.
String _sparse(DocumentTypeAdapter type, String source) {
  final String full = _modelText(type, source);
  final Object? tree = jsonDecode(source);
  void prune(Object? node) {
    if (node is Map<String, dynamic>) {
      final List<String> keys = node.keys.toList();
      for (final String key in keys) {
        if (_fixedKeys.contains(key)) continue;
        final Object? value = node.remove(key);
        if (_modelTextOrNull(type, huiWriteJson(tree)) == full) continue;
        node[key] = value;
        prune(value);
      }
      // A key put back went to the end; restore the file's order.
      final List<MapEntry<String, dynamic>> kept = <MapEntry<String, dynamic>>[
        for (final String key in keys)
          if (node.containsKey(key)) MapEntry<String, dynamic>(key, node[key]),
      ];
      node
        ..clear()
        ..addEntries(kept);
    } else if (node is List<dynamic>) {
      node.forEach(prune);
    }
  }

  prune(tree);
  return huiWriteJson(tree);
}

typedef _Leaf = ({List<Object> path, Object value});

/// Scalar leaves of [tree] with a changed value each.
List<_Leaf> _leaves(Object? tree) {
  final List<_Leaf> out = <_Leaf>[];
  void walk(Object? node, List<Object> path) {
    if (node is Map) {
      for (final MapEntry<Object?, Object?> entry in node.entries) {
        final String key = '${entry.key}';
        if (_fixedKeys.contains(key)) continue;
        walk(entry.value, <Object>[...path, key]);
      }
    } else if (node is List) {
      for (int index = 0; index < node.length; index++) {
        walk(node[index], <Object>[...path, index]);
      }
    } else if (node is bool) {
      out.add((path: path, value: !node));
    } else if (node is int) {
      out.add((path: path, value: node + 1));
    } else if (node is double) {
      out.add((path: path, value: node + 0.25));
    } else if (node is String) {
      out.add((path: path, value: '${node}z'));
    }
  }

  walk(tree, const <Object>[]);
  return out;
}

/// A deep copy of [tree] with [path] set to [value], creating missing maps.
Object? _setAt(Object? tree, List<Object> path, Object value) {
  final Object? copy = huiDeepCopy(tree);
  Object? node = copy;
  for (int index = 0; index < path.length - 1; index++) {
    final Object step = path[index];
    if (node is Map) {
      node = node.putIfAbsent(step, () => <String, dynamic>{});
    } else {
      node = (node! as List<Object?>)[step as int];
    }
  }
  if (node is Map) {
    node[path.last] = value;
  } else {
    (node! as List<Object?>)[path.last as int] = value;
  }
  return copy;
}

/// Paths of map keys [actual] holds and [expected] does not.
List<List<Object>> _addedKeys(Object? expected, Object? actual) {
  final List<List<Object>> out = <List<Object>>[];
  void walk(Object? want, Object? got, List<Object> path) {
    if (want is Map && got is Map) {
      for (final MapEntry<Object?, Object?> entry in got.entries) {
        final Object key = '${entry.key}';
        if (!want.containsKey(key)) {
          out.add(<Object>[...path, key]);
        } else {
          walk(want[key], entry.value, <Object>[...path, key]);
        }
      }
    } else if (want is List && got is List && want.length == got.length) {
      for (int index = 0; index < got.length; index++) {
        walk(want[index], got[index], <Object>[...path, index]);
      }
    }
  }

  walk(expected, actual, const <Object>[]);
  return out;
}

/// A deep copy of [tree] without the map keys at [paths].
Object? _without(Object? tree, List<List<Object>> paths) {
  final Object? copy = huiDeepCopy(tree);
  for (final List<Object> path in paths) {
    Object? node = copy;
    for (final Object step in path.sublist(0, path.length - 1)) {
      node = node is Map ? node[step] : (node! as List<Object?>)[step as int];
    }
    (node! as Map).remove(path.last);
  }
  return copy;
}

/// Whether [path] names one key in [tree]: every list on the way is in the
/// file. A leaf inside a whole list the file left out has no single key.
bool _singleKey(Object? tree, List<Object> path) {
  Object? node = tree;
  for (final (int index, Object step) in path.indexed) {
    if (node is Map) {
      if (!node.containsKey(step)) {
        return path.skip(index).every((Object rest) => rest is String);
      }
      node = node[step];
    } else if (node is List && step is int && step < node.length) {
      node = node[step];
    } else {
      return false;
    }
  }
  return true;
}

/// Every [n]th of [items], so a long document is sampled end to end.
List<T> _spread<T>(List<T> items, int n) {
  if (items.length <= n) return items;
  final double stride = items.length / n;
  return <T>[for (int i = 0; i < n; i++) items[(i * stride).floor()]];
}

void _checkFile(String name, String source) {
  final EditorStore store = _open(name, source);
  final DocumentTypeAdapter type = _typeOf(store);
  final String opened = store.workspace.active!.json;
  expect(opened, source, reason: '$name: opening keeps the file');
  final String model = _modelText(type, source);
  final Object? modelTree = jsonDecode(model);
  final Object? sourceTree = jsonDecode(source);
  final String baseHash = editorSyncDocumentHash(source);
  int checked = 0;
  // Only an edit the model takes as it is counts as one field: a value it
  // clamps, renames or rejects would change more than that key.
  final List<({_Leaf leaf, String edited})> edits =
      <({_Leaf leaf, String edited})>[
            for (final _Leaf leaf in _leaves(modelTree))
              if (_singleKey(sourceTree, leaf.path))
                (
                  leaf: leaf,
                  edited: huiWriteJson(
                    _setAt(modelTree, leaf.path, leaf.value),
                  ),
                ),
          ]
          .where(
            (({_Leaf leaf, String edited}) edit) =>
                _modelTextOrNull(type, edit.edited) == edit.edited,
          )
          .toList();
  for (final ({_Leaf leaf, String edited}) edit in _spread(
    edits,
    _leavesPerFile,
  )) {
    final _Leaf leaf = edit.leaf;
    final String edited = edit.edited;
    final String where = '$name ${leaf.path.join('.')}';
    checked++;

    _replace(store, type, edited);
    store.flushAutosave();
    final String savedText = store.workspace.active!.json;
    expect(
      _modelTextOrNull(type, savedText),
      edited,
      reason: '$where: the saved file reads back as the edit',
    );
    final Object? saved = jsonDecode(savedText);
    final Object? expected = _setAt(sourceTree, leaf.path, leaf.value);
    final List<List<Object>> added = _addedKeys(expected, saved);
    expect(
      _without(saved, added),
      expected,
      reason: '$where: only the edited key differs',
    );
    // A key the file left out comes back only when the model works it out
    // from others and would read the edit differently without it.
    for (final List<Object> key in added) {
      expect(
        _modelTextOrNull(
          type,
          huiWriteJson(_without(saved, <List<Object>>[key])),
        ),
        isNot(edited),
        reason: '$where: ${key.join('.')} was added but is not needed',
      );
    }

    _replace(store, type, model);
    store.flushAutosave();
    expect(
      store.workspace.active!.json,
      source,
      reason: '$where: typed back, the file is the opened bytes',
    );
    expect(editorSyncDocumentHash(store.workspace.active!.json), baseHash);

    _replace(store, type, edited);
    store.flushAutosave();
    expect(store.performUndo(), isTrue);
    store.flushAutosave();
    expect(
      store.workspace.active!.json,
      source,
      reason: '$where: undone, the file is the opened bytes',
    );
  }
  expect(checked, greaterThan(0), reason: '$name: no field could be edited');
}

void main() {
  final List<File> fixtures =
      Directory('test/demo/fixtures')
          .listSync()
          .whereType<File>()
          .where((File file) => file.path.endsWith('.json'))
          .toList()
        ..sort((File a, File b) => a.path.compareTo(b.path));

  test('the fixtures cover every kind with a runtime file', () {
    final Set<WorkspaceDocKind> kinds = <WorkspaceDocKind>{
      for (final File file in fixtures)
        _open(file.uri.pathSegments.last, file.readAsStringSync()).docKind,
    };
    expect(kinds, <WorkspaceDocKind>{
      for (final WorkspaceDocKind kind in WorkspaceDocKind.values)
        if (DocumentTypeRegistry.of(kind).transferable) kind,
    });
  });

  group('an edit changes its own key only', () {
    for (final File file in fixtures) {
      final String name = file.uri.pathSegments.last;
      test('$name as written', () => _checkFile(name, file.readAsStringSync()));
      test('$name without defaults', () {
        final String source = file.readAsStringSync();
        final EditorStore probe = _open(name, source);
        _checkFile(name, _sparse(_typeOf(probe), source));
      });
    }
  });

  test('hologram: a line typed back or undone matches the server file', () {
    // The shape a seeded server file has: no style defaults, no box.
    const String server =
        '{\n'
        '  "schemaVersion": 3,\n'
        '  "revision": 4,\n'
        '  "anchor": {"world": "world", "position": [12.5, 70, -3]},\n'
        '  "lines": ["&6Market", "&7Trade supplies here"],\n'
        '  "style": {"billboard": "center", "seeThrough": true}\n'
        '}\n';
    final EditorStore store = _open('seed-h142.json', server);
    store.mutateHologram('edit line', (GlossHologramDoc doc) {
      doc.lines[0] = '&6Bazaar';
    });
    store.flushAutosave();
    final Map<String, Object?> saved =
        jsonDecode(store.workspace.active!.json) as Map<String, Object?>;
    expect(saved['style'], <String, Object?>{
      'billboard': 'center',
      'seeThrough': true,
    });
    expect(saved.containsKey('box'), isFalse);
    expect(saved['lines'], <Object?>['&6Bazaar', '&7Trade supplies here']);

    expect(store.performUndo(), isTrue);
    store.flushAutosave();
    expect(store.workspace.active!.json, server);

    store.mutateHologram('edit line', (GlossHologramDoc doc) {
      doc.lines[0] = '&6Bazaar';
    });
    store.mutateHologram('edit line', (GlossHologramDoc doc) {
      doc.lines[0] = '&6Market';
    });
    store.flushAutosave();
    expect(store.workspace.active!.json, server);
  });

  test('hologram: setting a key the file left out writes that key only', () {
    const String server =
        '{"schemaVersion":3,"revision":1,'
        '"anchor":{"world":"world","position":[0,64,0]},'
        '"lines":["Hi"],"style":{"billboard":"center"}}';
    final EditorStore store = _open('seed-h1.json', server);
    store.mutateHologram('shadow', (GlossHologramDoc doc) {
      doc.style.shadow = true;
    });
    store.flushAutosave();
    final Map<String, Object?> saved =
        jsonDecode(store.workspace.active!.json) as Map<String, Object?>;
    expect(saved['style'], <String, Object?>{
      'billboard': 'center',
      'shadow': true,
    });
    expect(saved.keys, <String>[
      'schemaVersion',
      'revision',
      'anchor',
      'lines',
      'style',
    ]);
  });
}
