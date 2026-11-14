import 'package:gloss_editor/config/kind_groups.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

void main() {
  test('every kind belongs to exactly one group', () {
    final List<WorkspaceDocKind> listed = <WorkspaceDocKind>[
      for (final KindInfo info in kindInfos) info.kind,
    ];
    expect(listed.toSet(), WorkspaceDocKind.values.toSet());
    expect(listed.length, WorkspaceDocKind.values.length);
  });

  test('groups hold the kinds the plan names, in order', () {
    List<WorkspaceDocKind> members(KindGroup group) => <WorkspaceDocKind>[
      for (final KindInfo info in kindsIn(group, advanced: true)) info.kind,
    ];
    expect(members(KindGroup.screens), <WorkspaceDocKind>[
      WorkspaceDocKind.menu,
      WorkspaceDocKind.containerPreview,
      WorkspaceDocKind.inventory,
      WorkspaceDocKind.panel,
    ]);
    expect(members(KindGroup.hud), <WorkspaceDocKind>[
      WorkspaceDocKind.scoreboard,
      WorkspaceDocKind.tablist,
      WorkspaceDocKind.surface,
      WorkspaceDocKind.motd,
      WorkspaceDocKind.leaderboard,
    ]);
    expect(members(KindGroup.chat), <WorkspaceDocKind>[
      WorkspaceDocKind.channel,
      WorkspaceDocKind.connections,
      WorkspaceDocKind.emoji,
      WorkspaceDocKind.bubbleStyle,
    ]);
    expect(members(KindGroup.world), <WorkspaceDocKind>[
      WorkspaceDocKind.hologram,
      WorkspaceDocKind.marker,
      WorkspaceDocKind.waypoint,
      WorkspaceDocKind.damageIndicators,
      WorkspaceDocKind.realDrops,
      WorkspaceDocKind.entityOverlays,
    ]);
    expect(members(KindGroup.players), <WorkspaceDocKind>[
      WorkspaceDocKind.nameplate,
      WorkspaceDocKind.nametag,
    ]);
    expect(members(KindGroup.shared), <WorkspaceDocKind>[
      WorkspaceDocKind.animation,
      WorkspaceDocKind.strings,
      WorkspaceDocKind.names,
      WorkspaceDocKind.glyph,
      WorkspaceDocKind.behavior,
      WorkspaceDocKind.presets,
    ]);
  });

  test('Simple leaves out shared resources and flow maps', () {
    expect(groupsFor(advanced: false), isNot(contains(KindGroup.shared)));
    expect(groupsFor(advanced: true), KindGroup.values);
    expect(
      kindsIn(KindGroup.screens, advanced: false).map((KindInfo i) => i.kind),
      isNot(contains(WorkspaceDocKind.panel)),
    );
    for (final KindInfo info in kindInfos) {
      expect(
        info.advanced,
        info.group == KindGroup.shared || info.kind == WorkspaceDocKind.panel,
        reason: info.kind.name,
      );
    }
  });

  test('only menus and container previews have a distinct preview', () {
    expect(
      <WorkspaceDocKind>[
        for (final WorkspaceDocKind kind in WorkspaceDocKind.values)
          if (hasDistinctPreview(kind)) kind,
      ],
      <WorkspaceDocKind>[
        WorkspaceDocKind.menu,
        WorkspaceDocKind.containerPreview,
      ],
    );
  });

  test('catalog kinds have no inspector pane', () {
    expect(
      <WorkspaceDocKind>{
        for (final WorkspaceDocKind kind in WorkspaceDocKind.values)
          if (!kindHasInspector(kind)) kind,
      },
      <WorkspaceDocKind>{
        WorkspaceDocKind.names,
        WorkspaceDocKind.strings,
        WorkspaceDocKind.waypoint,
        WorkspaceDocKind.glyph,
        WorkspaceDocKind.behavior,
        WorkspaceDocKind.presets,
        WorkspaceDocKind.leaderboard,
      },
    );
  });

  test('New choices are the kind noun alone, in sentence case', () {
    for (final KindInfo info in kindInfos) {
      final String label = info.singular;
      expect(label, isNot(startsWith('New ')), reason: label);
      expect(label.trim(), label);
      expect(label[0], label[0].toUpperCase(), reason: label);
      final List<String> words = label.split(' ');
      for (final String word in words.skip(1)) {
        expect(
          word == word.toLowerCase() || word == word.toUpperCase(),
          isTrue,
          reason: '$label: "$word" is not sentence case',
        );
      }
    }
  });

  test('tips are one short line without em dashes', () {
    for (final KindInfo info in kindInfos) {
      expect(info.tip.length, lessThanOrEqualTo(80), reason: info.kind.name);
      expect(info.tip, isNot(contains('—')), reason: info.kind.name);
      expect(info.label, isNotEmpty);
      expect(info.singular, isNotEmpty);
    }
  });

  group('simpleLanding', () {
    WorkspaceDoc doc(String id, WorkspaceDocKind kind, int at) => WorkspaceDoc(
      id: id,
      title: id,
      runtimeId: null,
      json: '{}',
      updatedAt: at,
      folderId: null,
      kind: kind,
    );
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[
      doc('spin', WorkspaceDocKind.animation, 9),
      doc('lobby', WorkspaceDocKind.menu, 2),
      doc('sign', WorkspaceDocKind.hologram, 5),
    ];

    test('Simple lists every kind but flow maps and shared resources', () {
      expect(listedInMode(docs[0], advanced: false), isFalse);
      expect(listedInMode(docs[1], advanced: false), isTrue);
      expect(listedInMode(docs[0], advanced: true), isTrue);
    });

    test('keeps a document Simple shows', () {
      expect(simpleLanding(docs, 'lobby'), 'lobby');
    });

    test('moves off an Advanced kind to the newest document Simple shows', () {
      expect(simpleLanding(docs, 'spin'), 'sign');
    });

    test('lands nowhere when Simple shows no document', () {
      expect(simpleLanding(<WorkspaceDoc>[docs.first], 'spin'), isNull);
    });

    test('lands on the newest document Simple shows when none is open', () {
      expect(simpleLanding(docs, null), 'sign');
    });
  });
}
