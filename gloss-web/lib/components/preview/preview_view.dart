/// The menu's 3D preview: one [StageFrame] around the readout line, the stage
/// and the action log.
///
/// The stage is imperative DOM and must never be re-patched. The frame body
/// (readout, stage, log) is built once and handed to the frame as the same
/// instance on every header rebuild, so Jaspr skips it. Inside it the readout
/// and the log rebuild under their own [ListenableBuilder]s and the stage
/// under none. The header listens to the store, the pose and the log buffer;
/// the readout to the per-tick live state; the log to its buffer. A 60 fps
/// orbit drag writes none of them and rebuilds nothing (see
/// `preview_pose.dart`), and the header rebuilds only when what it shows
/// changes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Listenable, ListenableBuilder;

import '../../preview/preview_types.dart';
import '../../services/catalogs.dart';
import '../../services/image_library.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/menu.dart';
import '../../ui/stage_frame.dart';
import '../../ui/toggle_choice.dart';
import '../canvas/canvas_toolbar.dart' show uiScaleMenuEntries;
import '../shell/store_selector.dart';
import 'preview_header.dart';
import 'preview_hud.dart';
import 'preview_log_view.dart';
import 'preview_pose.dart';
import 'preview_stage.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class PreviewView extends StatefulWidget {
  const PreviewView({
    required this.store,
    required this.images,
    this.catalogs,
    super.key,
  });

  final EditorStore store;
  final ImageLibrary images;

  /// Null until the boot fetch lands; item sprites degrade to placeholders.
  final HuiCatalogs? catalogs;

  @override
  State<PreviewView> createState() => _PreviewViewState();
}

class _PreviewViewState extends State<PreviewView> {
  final PreviewPose _pose = huiPreviewPose;

  /// The frame body. Rebuilt only when an input of the stage changes; every
  /// header rebuild passes this same instance.
  late Widget _body = _buildBody();

  late Listenable _headerSource = _headerListenable();

  EditorStore get _store => component.store;

  @override
  void didUpdateWidget(PreviewView oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) {
      _headerSource = _headerListenable();
    }
    if (!identical(oldComponent.store, component.store) ||
        !identical(oldComponent.images, component.images) ||
        !identical(oldComponent.catalogs, component.catalogs)) {
      _body = _buildBody();
    }
  }

  Listenable _headerListenable() =>
      Listenable.merge(<Listenable>[component.store, _pose, _pose.log]);

  Widget _buildBody() {
    final EditorStore store = component.store;
    return dom.div(classes: 'hui-preview-body', <Widget>[
      ListenableBuilder(
        listenable: Listenable.merge(<Listenable>[store, _pose.live]),
        builder: (BuildContext _) => PreviewHud(store: store, pose: _pose),
      ),
      PreviewStage(
        store: store,
        images: component.images,
        pose: _pose,
        catalogs: component.catalogs,
      ),
      ListenableBuilder(
        listenable: Listenable.merge(<Listenable>[store, _pose.log]),
        builder: (BuildContext _) =>
            PreviewLogView(store: store, log: _pose.log),
      ),
    ]);
  }

  @override
  Widget build(BuildContext context) =>
      dom.div(classes: 'hui-preview', <Widget>[
        StoreSelector<PreviewHeaderState>(
          listenable: _headerSource,
          selector: () => previewHeaderState(_store, _pose),
          builder: (BuildContext _, PreviewHeaderState state) => _frame(state),
        ),
      ]);

  Widget _frame(PreviewHeaderState state) {
    final EditorStore store = _store;
    return StageFrame(
      title: huiText('Menu preview'),
      fit: StageFit.free,
      toggles: <Widget>[
        ToggleChoice<PreviewCameraMode>(
          label: huiText('Camera'),
          value: state.camera,
          onChanged: (PreviewCameraMode mode) => store.previewCameraMode = mode,
          options: <ChoiceOption<PreviewCameraMode>>[
            ChoiceOption<PreviewCameraMode>(
              PreviewCameraMode.orbit,
              huiText('Orbit'),
              icon: ArcaneIcon.orbit(),
              tip: huiText(
                'Orbit camera: drag to turn around the menu, scroll to dolly, space-drag to pan',
              ),
            ),
            ChoiceOption<PreviewCameraMode>(
              PreviewCameraMode.player,
              huiText('Player'),
              icon: ArcaneIcon.user(),
              tip: huiText(
                'Player camera: the view from eye height, 1.62 blocks above the feet the menu is anchored to',
              ),
            ),
          ],
        ),
      ],
      actions: <RowAction>[
        // The preview's own clock (K), separate from the canvas playback.
        RowAction(
          huiText('Play'),
          () => _pose.paused = state.playing,
          icon: ArcaneIcon.play(),
          iconOnly: true,
          pressed: state.playing,
        ),
        RowAction(
          huiText('Action log'),
          () => store.previewLogOpen = !state.logOpen,
          icon: ArcaneIcon.terminal(),
          iconOnly: true,
          pressed: state.logOpen,
        ),
      ],
      more: <AppMenuEntry>[
        AppMenuEntry(
          huiText('Reopen here'),
          () => _pose.controller?.reopenHere(),
          icon: ArcaneIcon.rotate3d(),
        ),
        AppMenuEntry(
          huiText('Clear log'),
          state.logEmpty ? null : _pose.log.clear,
          icon: ArcaneIcon.trash2(),
        ),
        const AppMenuEntry.separator(),
        AppMenuEntry.heading(huiText('Overlays')),
        for (int index = 0; index < previewOverlays.length; index++)
          AppMenuEntry.checkbox(
            previewOverlays[index].label(),
            checked: state.overlays & (1 << index) != 0,
            onChanged: (bool on) => previewOverlays[index].write(store, on),
            hint: previewOverlays[index].key,
          ),
        const AppMenuEntry.separator(),
        ...uiScaleMenuEntries(store),
      ],
      onReset: () => _pose.controller?.resetCamera(),
      status: huiText('Left or right click fires the nearest hitbox'),
      statusTip: huiText(
        'Orbit: drag turns, scroll dollies, space-drag pans. Player: click to take the mouse, WASD walks, Esc releases it. Shift tests sneak actions, 0 resets the view, 1 to 6 toggle overlays, K pauses. Icons and click planes keep their billboard facing.',
      ),
      child: _body,
    );
  }
}
