// The action list's pure parts: the presets in its Add menu, the workspace
// menus those presets open, the one-line summary on a collapsed row, and the
// row that stays open while rows move or go.
import 'package:gloss_editor/components/inspector/action_presets.dart';
import 'package:gloss_editor/logic/action_list.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

WorkspaceDoc _doc(
  String? runtimeId,
  int updatedAt, {
  WorkspaceDocKind kind = WorkspaceDocKind.menu,
}) => WorkspaceDoc(
  id: 'doc-$runtimeId-$updatedAt',
  title: runtimeId ?? 'untitled',
  runtimeId: runtimeId,
  json: '{}',
  updatedAt: updatedAt,
  folderId: null,
  kind: kind,
);

void main() {
  group('recentMenuIds', () {
    test('should list menus most recently edited first, at most six', () {
      final List<String> ids = recentMenuIds(<WorkspaceDoc>[
        for (int index = 0; index < 9; index++) _doc('menu-$index', index),
      ], exclude: 'none');
      expect(ids, <String>[
        'menu-8',
        'menu-7',
        'menu-6',
        'menu-5',
        'menu-4',
        'menu-3',
      ]);
    });

    test('should leave out the open menu, other kinds and unnamed docs', () {
      final List<String> ids = recentMenuIds(<WorkspaceDoc>[
        _doc('lobby', 9),
        _doc('board', 8, kind: WorkspaceDocKind.hologram),
        _doc(null, 7),
        _doc('shop/tools', 6),
      ], exclude: 'lobby');
      expect(ids, <String>['shop/tools']);
    });

    test('should break equal times by id', () {
      final List<String> ids = recentMenuIds(<WorkspaceDoc>[
        _doc('b', 1),
        _doc('a', 1),
      ], exclude: '');
      expect(ids, <String>['a', 'b']);
    });
  });

  group('actionPresets', () {
    test('should open this menu first, then each recent menu', () {
      final List<ActionPreset> presets = actionPresets(
        menuId: 'lobby',
        recentMenus: <String>['shop/tools'],
      );
      final HuiAction first = presets.first.build();
      expect(first, isA<HuiNavigateAction>());
      expect((first as HuiNavigateAction).target, 'lobby');
      expect(first.mode, 'push');
      expect(presets[1].label, 'Open tools');
      expect((presets[1].build() as HuiNavigateAction).target, 'shop/tools');
    });

    test('should build a fresh action on every pick', () {
      final ActionPreset preset = actionPresets(
        menuId: 'lobby',
        recentMenus: const <String>[],
      ).first;
      expect(identical(preset.build(), preset.build()), isFalse);
    });

    test('should end with an empty player and an empty console command', () {
      final List<ActionPreset> presets = actionPresets(
        menuId: 'lobby',
        recentMenus: const <String>[],
      );
      final HuiCommandAction player =
          presets[presets.length - 2].build() as HuiCommandAction;
      final HuiCommandAction console = presets.last.build() as HuiCommandAction;
      expect(player.command, isEmpty);
      expect(player.source, 'player');
      expect(console.command, isEmpty);
      expect(console.source, 'server');
    });
  });

  group('actionSummary', () {
    test('should show the value each action type is about', () {
      expect(actionSummary(HuiCommandAction('warp shop')), 'warp shop');
      expect(
        actionSummary(HuiSoundAction('ui.button.click')),
        'ui.button.click',
      );
      expect(actionSummary(HuiMessageAction('<gold>Hi')), '<gold>Hi');
      expect(actionSummary(HuiConnectAction('lobby')), 'lobby');
      expect(
        actionSummary(HuiTeleportAction('minecraft:overworld', 0, 64, 0, 0, 0)),
        'minecraft:overworld',
      );
    });

    test('should show a navigation target, or its mode when it has none', () {
      expect(actionSummary(HuiNavigateAction('shop', 'push')), 'shop');
      expect(actionSummary(HuiNavigateAction('', 'back')), 'back');
    });

    test('should keep only the first line of a long message', () {
      expect(actionSummary(HuiMessageAction('one\ntwo')), 'one');
    });

    test('should name a dialog by its title and a call by its action', () {
      final HuiRuntimeAction dialog = HuiRuntimeAction.fromMap(
        <String, Object?>{'type': 'dialog', 'title': 'Rules'},
      );
      final HuiRuntimeAction call = HuiRuntimeAction.fromMap(<String, Object?>{
        'type': 'call',
        'action': 'greet',
      });
      final HuiRuntimeAction stop = HuiRuntimeAction.fromMap(<String, Object?>{
        'type': 'stop',
      });
      expect(actionSummary(dialog), 'Rules');
      expect(actionSummary(call), 'greet');
      expect(actionSummary(stop), isEmpty);
    });
  });

  group('open row', () {
    test('should follow the open row through a move', () {
      // [a, b, c] with a moved to the end reads [b, c, a].
      expect(openRowAfterMove(0, from: 0, to: 2), 2);
      expect(openRowAfterMove(1, from: 0, to: 2), 0);
      expect(openRowAfterMove(2, from: 0, to: 2), 1);
      expect(openRowAfterMove(0, from: 2, to: 0), 1);
      expect(openRowAfterMove(null, from: 0, to: 1), isNull);
    });

    test('should close a removed row and shift the rows after it', () {
      expect(openRowAfterRemove(0, removed: 0), isNull);
      expect(openRowAfterRemove(2, removed: 0), 1);
      expect(openRowAfterRemove(0, removed: 1), 0);
      expect(openRowAfterRemove(null, removed: 0), isNull);
    });
  });
}
