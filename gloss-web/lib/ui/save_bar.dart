/// The sync bar at the bottom of the editor: one line of state and the
/// buttons it needs (Publish last), on a dense [ActionRow]. It shows while
/// the workspace is linked to a server project, or after a local save
/// failed ([showSaveBar]); the state comes from `save_state.dart`. The
/// buttons carry `data-sync-action=<SaveAction.name>`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import 'action_row.dart';
import 'save_state.dart';

ArcaneGlyph _icon(SaveAction action) => switch (action) {
  SaveAction.publish => ArcaneIcon.cloudUpload(),
  SaveAction.review => ArcaneIcon.listChecks(),
  SaveAction.newLink => ArcaneIcon.link(),
  SaveAction.reconnect => ArcaneIcon.refreshCw(),
  SaveAction.takeOver => ArcaneIcon.monitorUp(),
  SaveAction.retrySave => ArcaneIcon.rotateCw(),
  SaveAction.reload => ArcaneIcon.refreshCw(),
};

class SaveBar extends StatefulWidget {
  const SaveBar({required this.state, required this.onAction, super.key});

  final SaveBarState state;
  final void Function(SaveAction action) onAction;

  @override
  State<SaveBar> createState() => _SaveBarState();
}

class _SaveBarState extends State<SaveBar> {
  /// The shell builds again on every keystroke; the bar only when what it
  /// shows changes. Its buttons call the newest [SaveBar.onAction].
  @override
  bool shouldRebuild(SaveBar newComponent) =>
      newComponent.state != component.state;

  RowAction _action(SaveAction action, {required bool primary}) => RowAction(
    action.label,
    () => component.onAction(action),
    icon: primary ? null : _icon(action),
    loading: component.state.busy == action,
    attributes: <String, String>{
      'data-sync-action': action.name,
      if (primary && component.state.hint != null)
        'aria-description': component.state.hint!,
    },
  );

  @override
  Widget build(BuildContext context) {
    final SaveBarState state = component.state;
    return dom.footer(
      classes: 'ui-save-bar is-${state.tone.name}',
      attributes: <String, String>{
        'aria-label': state.message,
        'data-sync-tone': state.tone.name,
        if (state.status != null) 'data-sync-bar': '',
        'data-sync-status': ?state.status,
      },
      <Widget>[
        ActionRow(
          dense: true,
          leading: <Widget>[
            dom.p(
              classes: 'ui-save-state',
              attributes: <String, String>{
                'role': state.tone == SaveTone.failed ? 'alert' : 'status',
                'aria-live': 'polite',
              },
              <Widget>[
                const dom.span(
                  classes: 'ui-save-dot',
                  attributes: <String, String>{'aria-hidden': 'true'},
                  <Widget>[],
                ),
                dom.strong(classes: 'ui-save-message', <Widget>[
                  Component.text(state.message),
                ]),
                if (state.detail != null)
                  dom.span(classes: 'ui-save-detail', <Widget>[
                    Component.text(state.detail!),
                  ]),
              ],
            ),
          ],
          secondary: <RowAction>[
            for (final SaveAction action in state.secondary)
              _action(action, primary: false),
          ],
          primary: state.primary == null
              ? null
              : _action(state.primary!, primary: true),
        ),
      ],
    );
  }
}
