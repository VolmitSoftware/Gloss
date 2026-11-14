/// Confirms a menu that arrived through a `#/import/menu/` link before it
/// joins the workspace as a new document. The link never replaces one.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../model/model.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/mode.dart';
import '../../ui/technical_input.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class MenuHandoffDialog extends StatelessWidget {
  const MenuHandoffDialog({
    required this.envelope,
    required this.onConfirm,
    required this.onClose,
    super.key,
  });

  final WorkspaceMenuImportEnvelope? envelope;
  final VoidCallback onConfirm;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final WorkspaceMenuImportEnvelope? pending = envelope;
    final HuiMenu? menu = pending == null ? null : decodeHuiMenu(pending.json);
    return AppDialog(
      id: 'hui-menu-handoff-dialog',
      open: pending != null,
      onClose: onClose,
      title: huiText('Import menu from server'),
      footer: DialogFooter(
        cancel: RowAction(huiText('Cancel'), onClose),
        primary: RowAction(
          huiText('Add to workspace'),
          pending == null ? null : onConfirm,
          icon: ArcaneIcon.filePlus(),
        ),
      ),
      children: <Widget>[
        if (pending != null && menu != null) ...<Widget>[
          dom.p(classes: 'ui-section-description', <Widget>[
            Component.text(
              huiText('Review the menu, then add it as a new document.'),
            ),
          ]),
          Field(
            label: huiText('Runtime id'),
            forId: 'hui-menu-handoff-id',
            tip: huiText(
              'The runtime id is preserved exactly, including nested '
              'folder segments.',
            ),
            child: TextInput(
              id: 'hui-menu-handoff-id',
              value: pending.runtimeId,
              readOnly: true,
              fullWidth: true,
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
            ),
          ),
          Field(
            label: huiText('Components'),
            child: Component.text('${menu.components.length}'),
          ),
          AdvancedOnly(
            child: FieldGroup(
              title: huiText('Menu JSON'),
              children: <Widget>[
                dom.pre(
                  classes: 'hui-codeblock is-scroll',
                  attributes: const <String, String>{'dir': 'ltr'},
                  <Widget>[
                    Component.text(
                      pending.json.length <= 12000
                          ? pending.json
                          : '${pending.json.substring(0, 12000)}\n…',
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}
