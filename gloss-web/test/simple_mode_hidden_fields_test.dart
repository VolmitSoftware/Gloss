/// "Hidden never means lost" (lib/ui/mode.dart): a Simple-mode edit writes
/// one key, so the fields Simple hides (AdvancedOnly, MoreFields) and the
/// keys no editor knows come back out of the save untouched.
///
/// Each test loads a document holding both, makes one edit through the write
/// path the inspector's onChanged calls, and compares the saved text with the
/// store's own text from before the edit: byte for byte, except the edited
/// key.
library;

import 'dart:convert';

import 'package:gloss_editor/config/defaults.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

class _FakeStorage {
  final Map<String, String> values = <String, String>{};

  String? read(String key) => values[key];

  bool write(String key, String value) {
    values[key] = value;
    return true;
  }
}

/// [source] imported as a new document, the way a dropped file arrives.
EditorStore _open(
  String file,
  Map<String, Object?> source,
  WorkspaceDocKind kind,
) {
  final _FakeStorage storage = _FakeStorage();
  final EditorStore store = EditorStore(
    workspace: Workspace(read: storage.read, write: storage.write),
    autosaveDelay: Duration.zero,
  );
  addTearDown(store.dispose);
  expect(store.importJsonAsNewDocument(file, huiWriteJson(source)), isTrue);
  expect(store.docKind, kind);
  return store;
}

/// A deep copy of [tree] with the value at [path] replaced by [value], or
/// removed when [remove] is set. Every other map key keeps its place.
Object? _edited(
  Object? tree,
  List<Object> path, {
  Object? value,
  bool remove = false,
}) {
  final Object? copy = huiDeepCopy(tree);
  Object? parent = copy;
  for (final Object step in path.sublist(0, path.length - 1)) {
    parent = parent is Map ? parent[step] : (parent! as List<Object?>)[step as int];
  }
  final Object last = path.last;
  if (parent is Map) {
    if (remove) {
      parent.remove(last);
    } else {
      parent[last] = value;
    }
  } else {
    (parent! as List<Object?>)[last as int] = value;
  }
  return copy;
}

Object? _at(Object? tree, List<Object> path) {
  Object? node = tree;
  for (final Object step in path) {
    node = switch (node) {
      final Map<Object?, Object?> map => map[step],
      final List<Object?> list => list[step as int],
      _ => null,
    };
  }
  return node;
}

/// [value] with every map's keys sorted, in the store's encoder: two trees
/// with the same text hold the same keys and values, whatever their order.
String _canonical(Object? value) {
  Object? sorted(Object? node) {
    if (node is Map) {
      final List<String> keys = <String>[
        for (final Object? key in node.keys) '$key',
      ]..sort();
      return <String, Object?>{
        for (final String key in keys) key: sorted(node[key]),
      };
    }
    if (node is List) {
      return <Object?>[for (final Object? item in node) sorted(item)];
    }
    return node;
  }

  return huiWriteJson(sorted(value));
}

/// Runs [edit], saves, and checks the saved file: the edited key holds
/// [value] (or is gone when [remove] is set) and every other byte matches
/// the store's text from before the edit. The load is checked first, so a
/// key dropped on the way in cannot hide behind an equal before and after.
void _expectOnlyKeyChanged(
  EditorStore store,
  Map<String, Object?> source,
  List<Object> path,
  void Function() edit, {
  Object? value,
  bool remove = false,
}) {
  final String before = store.exportJson();
  expect(
    _canonical(jsonDecode(before)),
    _canonical(source),
    reason: 'loading kept every key of the file',
  );

  edit();
  store.flushAutosave();
  final String saved = store.workspace.active!.json;

  expect(store.exportJson(), saved);
  final Object? decoded = jsonDecode(saved);
  if (remove) {
    final Object? parent = _at(decoded, path.sublist(0, path.length - 1));
    expect(
      parent,
      isA<Map<String, Object?>>(),
      reason: 'the object holding ${path.join('.')} is still saved',
    );
    expect(
      (parent! as Map<String, Object?>).containsKey(path.last),
      isFalse,
      reason: 'the edit removed ${path.join('.')}',
    );
  } else {
    expect(
      _at(decoded, path),
      value,
      reason: 'the edit landed at ${path.join('.')}',
    );
  }
  expect(
    saved,
    huiWriteJson(
      _edited(jsonDecode(before), path, value: value, remove: remove),
    ),
  );
}

/// A menu whose `buy` button carries what the menu and component inspectors
/// hide in Simple, plus unknown keys on the menu, the component, its data
/// and the hitbox the edit writes into.
Map<String, Object?> _menuSource({required List<double> hitboxOffset}) {
  final HuiMenu menu = createDefaultMenu();
  menu.components.add(
    HuiComponent(
      'buy',
      Vec3(0.5, 0.2, 0),
      HuiButtonData(
        0.05,
        <HuiAction>[],
        HuiTextIcon('&aBuy'),
        HuiHitbox(
          null,
          null,
          Vec3(hitboxOffset[0], hitboxOffset[1], hitboxOffset[2]),
        ),
      ),
    ),
  );
  final Map<String, Object?> source = menu.toJson();
  // menu_inspector.dart: GlossVisibilityEditor and the Extra keys section
  // are AdvancedOnly.
  source['show'] = 'viewer.level >= 5';
  source['futureMenuKey'] = <String, Object?>{
    'kept': true,
    'list': <Object?>[1, 'two'],
  };
  // menu_variants_editor.dart: the variant id, priority and components
  // JSON are AdvancedOnly; only the condition shows in Simple.
  source['variants'] = <Object?>[
    <String, Object?>{
      'id': 'night',
      'priority': 4,
      'when': 'world.time > 13000',
      'components': <Object?>[menu.components.first.toJson()],
      'futureVariantKey': 'kept',
    },
  ];
  final Map<String, Object?> button =
      (source['components']! as List<Object?>)[1]! as Map<String, Object?>;
  // component_inspector.dart: the component's visibility, its motion
  // easing ticks and its Extra keys are AdvancedOnly.
  button['show'] = 'viewer.money >= 10';
  button['motion'] = <String, Object?>{
    'y': 'sin(tick / 10) * 0.05',
    'ticks': 6,
    'futureMotionKey': 'kept',
  };
  button['futureComponentKey'] = 7;
  final Map<String, Object?> data = button['data']! as Map<String, Object?>;
  // component_inspector.dart: the button tooltip editor is AdvancedOnly.
  data['tooltip'] = <String, Object?>{
    'delayTicks': 10,
    'lines': <Object?>['&7Costs 10 coins'],
  };
  data['futureDataKey'] = <String, Object?>{'nested': <Object?>[]};
  final Map<String, Object?> hitbox = data['hitbox']! as Map<String, Object?>;
  hitbox['futureHitboxKey'] = <String, Object?>{'glow': true};
  return source;
}

/// A scoreboard with the Advanced-only layout, objectives, variant id and
/// priority, and unknown keys on the root, the selection, the default
/// presentation the edit writes into, and a variant.
Map<String, Object?> _scoreboardSource() => <String, Object?>{
  'schemaVersion': glossScoreboardCurrentSchemaVersion,
  'revision': 7,
  'select': <String, Object?>{
    'priority': 2,
    'when': 'true',
    'futureSelectKey': 'kept',
  },
  'presentation': <String, Object?>{
    'title': '&6Stats',
    'lines': <Object?>[
      '&fKills: 3',
      <String, Object?>{'text': '&fCoins', 'value': '%coins%', 'id': 'coins'},
    ],
    'hideNumbers': false,
    // scoreboard_inspector.dart: ScoreboardLayoutEditor is AdvancedOnly.
    'layout': <String, Object?>{
      'pages': <Object?>[
        <String, Object?>{
          'id': 'intro',
          'lines': <Object?>['&eWelcome'],
          'durationTicks': 100,
        },
      ],
      'overflow': 'truncate',
      'refresh': <String, Object?>{'valueTicks': 20},
    },
    'futurePresentationKey': <String, Object?>{'a': 1},
  },
  'variants': <Object?>[
    <String, Object?>{
      // scoreboard_inspector.dart: a variant's id and priority are
      // AdvancedOnly.
      'id': 'low-health',
      'priority': 5,
      'when': 'viewer.healthPercent <= 25',
      'presentation': <String, Object?>{
        'title': '&cDanger',
        'lines': <Object?>['&fHeal up'],
        'hideNumbers': true,
      },
      'futureVariantKey': true,
    },
  ],
  // gloss_visibility_editor.dart: the show condition is AdvancedOnly.
  'show': 'viewer.world == "world"',
  // scoreboard_inspector.dart: ScoreboardObjectivesEditor is AdvancedOnly.
  'objectives': <String, Object?>{
    'playerList': <String, Object?>{'title': 'Kills', 'value': '%kills%'},
  },
  'futureRootKey': <Object?>[
    1,
    'two',
    <String, Object?>{'three': 3},
  ],
};

/// A marker with the Advanced-only show condition and Settings block
/// (beam style, edge, trail, icon, lifetime), keys no editor shows, and
/// unknown keys on the root and inside the beam the edit writes into.
Map<String, Object?> _markerSource() {
  final Map<String, Object?> source = GlossMarkerDoc(
    revision: 4,
    label: '&eSpawn',
    anchor: GlossMarkerAnchor(world: 'world', x: 10, y: 70, z: -4),
    distanceScale: 'distance / 16',
    hideWithin: 3,
    maxDistance: 128,
    // lane_inspectors.dart: only `enabled` of the beam shows in Simple; the
    // rest sits in the AdvancedOnly Settings block.
    beam: GlossMarkerBeam(
      glowColor: '#FF55FF',
      height: 64,
      width: 0.5,
      material: 'minecraft:red_stained_glass',
      extras: <String, Object?>{'futureBeamKey': <Object?>[1, 2, 3]},
    ),
    edge: GlossMarkerEdge(enabled: true, margin: 0.6, arrow: '&a>'),
    trail: GlossMarkerTrail(enabled: true, color: '#00FF00', maxPoints: 12),
    lifetimeTicks: 1200,
    waypoint: true,
  ).toJson();
  // lane_inspectors.dart: the show condition and the icon (in Settings) are
  // AdvancedOnly.
  source['show'] = 'viewer.permission("spawn.marker")';
  source['icon'] = <String, Object?>{'type': 'text', 'text': '&e*'};
  source['futureRootKey'] = <String, Object?>{'kept': 'yes'};
  return source;
}

/// A behavior catalog with the Advanced-only matching limits and trigger
/// options (keys a trigger does not accept), and unknown keys on the root,
/// the limits and the trigger entry the edit rebuilds.
Map<String, Object?> _behaviorSource() => <String, Object?>{
  'futureRootKey': 'kept',
  'schemaVersion': 2,
  'revision': 3,
  'enabled': true,
  'allowServerCommands': false,
  // behavior_inspector.dart: the Chat matching section is AdvancedOnly.
  'matching': <String, Object?>{
    'futureMatchingKey': 9,
    'syntax': 're2',
    'maxInputCharacters': 2048,
    'maxPatternCharacters': 512,
    'maxProgramSize': 8192,
    'maxNestingDepth': 16,
    'maxWorkUnits': 1000000,
  },
  'state': <String, Object?>{
    'visits': <String, Object?>{'type': 'int', 'default': 0},
  },
  'on': <Object?>[
    <String, Object?>{
      // behavior_inspector.dart: keys an interval trigger does not accept
      // only show in the AdvancedOnly "Trigger options" editor.
      'futureTriggerKey': <String, Object?>{'jitter': 5},
      'trigger': 'interval',
      'scope': 'player',
      'when': 'viewer.online',
      'everyTicks': 40,
      'do': <Object?>[
        <String, Object?>{'type': 'message', 'text': '&aStill here'},
      ],
    },
  ],
};

void main() {
  group('a Simple-mode edit keeps hidden and unknown keys', () {
    test('menu: moving a button hitbox writes only its offset', () {
      final Map<String, Object?> source = _menuSource(
        hitboxOffset: <double>[0.1, 0, 0],
      );
      final EditorStore store = _open(
        'shop.json',
        source,
        WorkspaceDocKind.menu,
      );
      // component_inspector.dart, Hitbox section: the Vec3Field's onChanged
      // is `store.setHitboxOffset(_id, value)`.
      _expectOnlyKeyChanged(
        store,
        source,
        <Object>['components', 1, 'data', 'hitbox', 'offset'],
        () => store.setHitboxOffset('buy', Vec3(0.25, -0.1, 0.05)),
        value: <double>[0.25, -0.1, 0.05],
      );
    });

    test('menu: resetting a hitbox offset keeps the hitbox unknown keys', () {
      final Map<String, Object?> source = _menuSource(
        hitboxOffset: <double>[0.1, 0, 0],
      );
      final EditorStore store = _open(
        'shop.json',
        source,
        WorkspaceDocKind.menu,
      );
      // A zero offset is the default and leaves the key out; the hitbox
      // itself must stay for the key it still holds.
      _expectOnlyKeyChanged(
        store,
        source,
        <Object>['components', 1, 'data', 'hitbox', 'offset'],
        () => store.setHitboxOffset('buy', Vec3.zero()),
        remove: true,
      );
    });

    test('scoreboard: retitling the default presentation writes only the '
        'title', () {
      final Map<String, Object?> source = _scoreboardSource();
      final EditorStore store = _open(
        'stats.json',
        source,
        WorkspaceDocKind.scoreboard,
      );
      // scoreboard_inspector.dart: the title input calls `mutateDefault`,
      // which is `_store.mutateScoreboard('scoreboard default presentation',
      // (edited) => mutate(edited.presentation))` with
      // `edited.title = value`.
      _expectOnlyKeyChanged(
        store,
        source,
        <Object>['presentation', 'title'],
        () => store.mutateScoreboard(
          'scoreboard default presentation',
          (GlossScoreboardDoc edited) =>
              edited.presentation.title = '&6&lStats',
        ),
        value: '&6&lStats',
      );
    });

    test('marker: switching the beam on writes only beam.enabled', () {
      final Map<String, Object?> source = _markerSource();
      final EditorStore store = _open(
        'spawn.json',
        source,
        WorkspaceDocKind.marker,
      );
      // lane_inspectors.dart, MarkerInspector: the Beam switch's onChanged
      // is `store.mutateMarker('beam', (edited) => edited.beam.enabled =
      // value)`.
      _expectOnlyKeyChanged(
        store,
        source,
        <Object>['beam', 'enabled'],
        () => store.mutateMarker(
          'beam',
          (GlossMarkerDoc edited) => edited.beam.enabled = true,
        ),
        value: true,
      );
    });

    test('behavior: changing a trigger interval writes only everyTicks', () {
      final Map<String, Object?> source = _behaviorSource();
      final EditorStore store = _open(
        'behaviors.json',
        source,
        WorkspaceDocKind.behavior,
      );
      // behavior_inspector.dart, `_entry`: the accepted-option text field
      // runs `_edit(label, ...)`, which is `store.mutateGloss`. Its body
      // lives in a private State class, so its `everyTicks` branch is
      // repeated here: rebuild the entry from its own JSON with one key set.
      _expectOnlyKeyChanged(
        store,
        source,
        <Object>['on', 0, 'everyTicks'],
        () => store.mutateGloss('everyTicks 1', (GlossDoc doc) {
          final GlossBehaviorDoc behavior = doc as GlossBehaviorDoc;
          final Map<String, Object?> next = behavior.on[0].toJson();
          next['everyTicks'] = int.parse('100');
          behavior.on[0] = GlossBehaviorEntry.fromJson(next);
        }),
        value: 100,
      );
    });
  });
}
