/// One-click starting points for a click action list, listed in its Add
/// action menu.
///
/// Navigation presets use the runtime's native page action. Move remains a
/// player command because it changes the live personal session's anchor.
/// Command presets always write `source`, so the file says how they run.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../doctype/doctype.dart';
import '../../model/model.dart';
import '../../state/workspace.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ActionPreset {
  const ActionPreset(this.label, this.icon, this.build);

  final String label;
  final ArcaneGlyph icon;

  /// A new action each call, so two picks never share one object.
  final HuiAction Function() build;
}

/// Runtime ids of the workspace menus, most recently edited first, at most
/// [limit], without [exclude] (the open menu, which has its own preset).
List<String> recentMenuIds(
  Iterable<WorkspaceDoc> docs, {
  required String exclude,
  int limit = 6,
}) {
  final List<WorkspaceDoc> menus = <WorkspaceDoc>[
    for (final WorkspaceDoc doc in docs)
      if (doc.kind == DocumentTypes.menu.kind &&
          doc.runtimeId != null &&
          doc.runtimeId != exclude)
        doc,
  ];
  menus.sort((WorkspaceDoc left, WorkspaceDoc right) {
    final int recent = right.updatedAt.compareTo(left.updatedAt);
    return recent != 0 ? recent : left.runtimeId!.compareTo(right.runtimeId!);
  });
  return <String>[
    for (final WorkspaceDoc doc in menus.take(limit)) doc.runtimeId!,
  ];
}

/// The presets for a menu whose id is [menuId]: open it, open each of
/// [recentMenus], back, close, move here, tell the player, teleport, connect,
/// then an empty command as the player and one from the console.
List<ActionPreset> actionPresets({
  required String menuId,
  required List<String> recentMenus,
}) => <ActionPreset>[
  ActionPreset(
    huiText('Open menu'),
    ArcaneIcon.externalLink(),
    () => HuiNavigateAction(menuId, 'push'),
  ),
  for (final String target in recentMenus)
    ActionPreset(
      huiText('Open {last}', <String, Object?>{'last': target.split('/').last}),
      ArcaneIcon.folderInput(),
      () => HuiNavigateAction(target, 'push'),
    ),
  ActionPreset(
    huiText('Back'),
    ArcaneIcon.undo(),
    () => HuiNavigateAction('', 'back'),
  ),
  ActionPreset(
    huiText('Close'),
    ArcaneIcon.x(),
    () => HuiNavigateAction('', 'close'),
  ),
  ActionPreset(
    huiText('Move here'),
    ArcaneIcon.move(),
    () => HuiCommandAction('gloss menu move', 'player'),
  ),
  ActionPreset(
    huiText('Tell player'),
    ArcaneIcon.messageCircle(),
    () => HuiMessageAction('<gold>Hello <white>%player%</white></gold>'),
  ),
  ActionPreset(
    huiText('Teleport'),
    ArcaneIcon.locateFixed(),
    () => HuiTeleportAction('minecraft:overworld', 0, 64, 0, 0, 0),
  ),
  ActionPreset(
    huiText('Connect server'),
    ArcaneIcon.share2(),
    () => HuiConnectAction('lobby'),
  ),
  ActionPreset(
    huiText('Run as player'),
    ArcaneIcon.mousePointerClick(),
    () => HuiCommandAction('', 'player'),
  ),
  ActionPreset(
    huiText('Run as console'),
    ArcaneIcon.terminal(),
    () => HuiCommandAction('', 'server'),
  ),
];
