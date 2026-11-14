/// The bottom status strip: the problems count (opens the validation
/// panel), the zoom while a pan/zoom stage shows, the autosave state; in
/// Advanced also the pointer readout and the build badge. On phones the
/// pointer readout hides and the save state says it in one word.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Listenable;

import '../../config/build_info.dart';
import '../../services/page_reload.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/save_state.dart';
import 'shell_status.dart';
import 'store_selector.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

class StatusBar extends StatelessWidget {
  const StatusBar({
    required this.store,
    this.status,
    this.onOpenValidation,
    super.key,
  });

  final EditorStore store;

  /// Canvas-owned readouts; null hides them.
  final ShellStatus? status;
  final void Function()? onOpenValidation;

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    return DenseBox(
      classes: 'hui-status',
      attributes: <String, String>{
        'role': 'contentinfo',
        'aria-label': huiText('Editor status'),
        // On the screen's bottom edge: touch hit areas grow upward.
        'data-touch-edge': 'bottom',
      },
      children: <Widget>[
        StoreSelector<String>(
          listenable: store,
          selector: () =>
              '${store.docKind.name}|${store.errorCount}|'
              '${store.warningCount}|${store.isPanelDoc}',
          builder: (BuildContext context, String _) => _problems(),
        ),
        const dom.span(classes: 'hui-status-spacer', <Widget>[]),
        _readouts(advanced),
        StoreSelector<String>(
          listenable: Listenable.merge(<Listenable?>[store, store.workspace]),
          selector: () =>
              '${store.hasUnsavedChanges}|'
              '${store.lastSavedAt?.millisecondsSinceEpoch}|'
              '${store.workspace.lastError}|${store.workspace.requiresReload}',
          builder: (BuildContext context, String _) => _save(),
        ),
        if (advanced) const StatusBadge(huiBuildBadge),
      ],
    );
  }

  /// Flow maps show their diagnostics on the map itself.
  Widget _problems() {
    if (store.isPanelDoc) return const dom.span(<Widget>[]);
    final int errors = store.errorCount;
    final int warnings = store.warningCount;
    final bool clean = errors == 0 && warnings == 0;
    final String label = clean
        ? huiText('No issues')
        : <String>[
            if (errors > 0)
              huiPlural(
                'status.errors.count',
                errors,
                oneEnglish: '{count} error',
                otherEnglish: '{count} errors',
              ),
            if (warnings > 0)
              huiPlural(
                'status.warnings.count',
                warnings,
                oneEnglish: '{count} warning',
                otherEnglish: '{count} warnings',
              ),
          ].join(', ');
    // The checks and the scripts find the control by this class.
    return dom.span(classes: 'hui-status-problems', <Widget>[
      Button.ghost(
        label: label,
        icon:
            (clean
                    ? ArcaneIcon.circleCheck()
                    : errors > 0
                    ? ArcaneIcon.circleAlert()
                    : ArcaneIcon.triangleAlert())
                .sized(controlIconSize),
        size: buttonSize(Density.dense),
        attributes: const <String, String>{
          'aria-controls': 'hui-validation',
          'aria-haspopup': 'dialog',
        },
        onPressed: onOpenValidation,
      ),
    ]);
  }

  Widget _readouts(bool advanced) {
    final ShellStatus? live = status;
    if (live == null) return const dom.span(<Widget>[]);
    return StoreSelector<String>(
      listenable: Listenable.merge(<Listenable?>[store, live]),
      selector: () =>
          '${_stageShows()}|${live.pointerX}|${live.pointerY}|${live.zoom}',
      builder: (BuildContext context, String _) {
        if (!_stageShows()) return const dom.span(<Widget>[]);
        return dom.span(classes: 'hui-status-readouts', <Widget>[
          if (advanced)
            dom.span(classes: 'hui-status-item hui-status-pointer is-numeric', <
              Widget
            >[
              Text(
                live.hasPointer
                    ? 'x ${_block(live.pointerX!)}  y ${_block(live.pointerY!)}'
                    : 'x --  y --',
              ),
            ]),
          dom.span(classes: 'hui-status-item is-numeric', <Widget>[
            Text(
              huiText('{round}%', <String, Object?>{
                'round': (live.zoom * 100).round(),
              }),
            ),
          ]),
        ]);
      },
    );
  }

  /// The pan/zoom canvas or container card is in the centre: the open
  /// menu or container preview in Edit or Split.
  bool _stageShows() =>
      store.hasActiveDocument &&
      (store.isMenuDoc || store.isPreviewDoc) &&
      (store.view == EditorView.visual || store.view == EditorView.split);

  /// The autosave line, and its one-word form for phones; after a failed
  /// save, the way out beside it (the save bar says what failed).
  Widget _save() {
    final SaveBarState state = localSaveState(
      saving: store.hasUnsavedChanges,
      failure: store.workspace.lastError,
      requiresReload: store.workspace.requiresReload,
      savedAt: store.lastSavedAt,
    );
    final bool failed = state.tone == SaveTone.failed;
    return dom.span(
      classes: 'hui-status-save${failed ? ' is-error' : ''}',
      attributes: const <String, String>{
        'role': 'status',
        'aria-live': 'polite',
        'aria-atomic': 'true',
      },
      <Widget>[
        dom.span(classes: 'hui-status-long', <Widget>[Text(state.message)]),
        dom.span(classes: 'hui-status-short', <Widget>[
          Text(switch (state.tone) {
            SaveTone.quiet => huiText('Saved'),
            SaveTone.failed => '',
            _ => state.message,
          }),
        ]),
        if (failed)
          AppIconButton(
            icon: ArcaneIcon.rotateCw(),
            label: store.workspace.requiresReload
                ? huiText('Reload')
                : huiText('Retry'),
            onPressed: store.workspace.requiresReload
                ? reloadEditorPage
                : _retryAutosave,
          ),
      ],
    );
  }

  static String _block(double value) => value.toStringAsFixed(2);

  Future<void> _retryAutosave() async {
    final bool saved = await store.retryAutosave();
    if (saved) {
      toastSuccess(huiText('Workspace saved.'));
      return;
    }
    toastError(
      store.workspace.lastError ?? huiText('Workspace save failed.'),
    );
  }
}
