/// The frame every canvas and preview sits in: a dense header with the
/// title, toggles (Grid, Hitboxes), actions, Reset view and Fullscreen; the
/// body; one status line under it. The body never shows a scrollbar: in
/// [StageFit.fill] it takes the height the centre pane gives it and the
/// content scales to fit inside ([FitBox]); in [StageFit.grow] the content
/// scales to the width and the frame grows to its height; in
/// [StageFit.free] the content sizes and clips itself (a WebGL stage, the
/// pan-and-zoom canvas). Loading, empty and failed previews show in the
/// body instead of the content. The body is marked `data-preview-frame`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../l10n/hui_localizations.dart';
import 'action_row.dart';
import 'alert.dart';
import 'fit_box.dart';
import 'info_tip.dart';
import 'menu.dart';
import 'skeleton.dart';
import 'toggle_choice.dart';

enum StageState { ready, loading, empty, error }

/// How the body sizes its content.
enum StageFit { fill, grow, free }

int _nextStageId = 0;

class StageFrame extends StatefulWidget {
  const StageFrame({
    required this.title,
    required this.child,
    this.fit = StageFit.fill,
    this.pixel = 0,
    this.header = true,
    this.surface = true,
    this.toggles = const <Widget>[],
    this.actions = const <RowAction>[],
    this.more = const <AppMenuEntry>[],
    this.onReset,
    this.fullscreen = true,
    this.status,
    this.statusTip,
    this.state = StageState.ready,
    this.emptyMessage,
    this.error,
    this.onRetry,
    this.id,
    super.key,
  });

  /// Names the frame; shown in the header.
  final String title;

  /// The canvas or the preview.
  final Widget child;
  final StageFit fit;

  /// CSS pixels per art pixel for pixel art in [StageFit.fill] and
  /// [StageFit.grow] (see [FitBox.pixel]).
  final int pixel;

  /// Draws the header row. Off where a toolbar elsewhere drives the stage.
  final bool header;

  /// Draws the frame's border and card fill. Off for a diagram whose boxes
  /// are the only surface, so no box sits in a second one.
  final bool surface;

  /// Controls that change what the stage shows (a [ToggleChoice]), left in
  /// the header after the title.
  final List<Widget> toggles;

  /// Header buttons, before Reset view and Fullscreen.
  final List<RowAction> actions;

  /// Entries of the header's `...` menu.
  final List<AppMenuEntry> more;

  /// Puts the camera back; null hides Reset view.
  final void Function()? onReset;

  /// Offers Fullscreen for the whole frame.
  final bool fullscreen;

  /// One short line under the stage; details go in [statusTip].
  final String? status;
  final String? statusTip;
  final StageState state;

  /// For [StageState.empty]; defaults to "Nothing to preview".
  final String? emptyMessage;

  /// What failed, for [StageState.error].
  final String? error;
  final void Function()? onRetry;

  /// On the frame's `section`.
  final String? id;

  @override
  State<StageFrame> createState() => _StageFrameState();
}

class _StageFrameState extends State<StageFrame> {
  late final String _id = component.id ?? 'ui-stage-${_nextStageId++}';

  void _toggleFullscreen() {
    final web.Element? frame = web.document.getElementById(_id);
    if (frame == null) return;
    if (web.document.fullscreenElement != null) {
      web.document.exitFullscreen();
    } else {
      frame.requestFullscreen();
    }
  }

  Widget _body() {
    final StageFrame frame = component;
    return switch (frame.state) {
      StageState.loading => Skeleton(height: '100%', label: huiText('Loading')),
      StageState.empty => dom.p(classes: 'ui-stage-empty', <Widget>[
        Component.text(frame.emptyMessage ?? huiText('Nothing to preview')),
      ]),
      StageState.error => AppAlert(
        tone: AlertTone.error,
        message: frame.error ?? huiText('The preview could not load.'),
        live: true,
        action: frame.onRetry == null
            ? null
            : Button.outline(
                label: huiText('Retry'),
                size: ButtonSize.sm,
                onPressed: frame.onRetry,
              ),
      ),
      StageState.ready => switch (frame.fit) {
        StageFit.fill => FitBox(
          fill: true,
          pixel: frame.pixel,
          child: frame.child,
        ),
        StageFit.grow => FitBox(pixel: frame.pixel, child: frame.child),
        StageFit.free => frame.child,
      },
    };
  }

  @override
  Widget build(BuildContext context) {
    final StageFrame frame = component;
    // On/off actions read as one segmented control after the title; the
    // one-shot actions stay buttons at the end.
    final List<RowAction> switches = <RowAction>[
      for (final RowAction action in frame.actions)
        if (action.pressed != null && action.icon != null) action,
    ];
    final List<RowAction> actions = <RowAction>[
      for (final RowAction action in frame.actions)
        if (!switches.contains(action)) action,
      if (frame.onReset != null)
        RowAction(
          huiText('Reset view'),
          frame.onReset,
          icon: ArcaneIcon.rotateCcw(),
          iconOnly: true,
        ),
      if (frame.fullscreen)
        RowAction(
          huiText('Fullscreen'),
          _toggleFullscreen,
          icon: ArcaneIcon.maximize(),
          iconOnly: true,
        ),
    ];
    return dom.section(
      id: _id,
      classes: 'ui-stage is-${frame.fit.name}${frame.surface ? '' : ' is-bare'}',
      attributes: <String, String>{'aria-label': frame.title},
      <Widget>[
        if (frame.header)
          dom.header(classes: 'ui-stage-head', <Widget>[
            ActionRow(
              dense: true,
              label: frame.title,
              leading: <Widget>[
                dom.h2(classes: 'ui-stage-title', <Widget>[
                  Component.text(frame.title),
                ]),
                ...frame.toggles,
                if (switches.isNotEmpty)
                  ToggleChips<int>(
                    label: huiText('Show'),
                    joined: true,
                    options: <ChoiceOption<int>>[
                      for (int index = 0; index < switches.length; index++)
                        ChoiceOption<int>(
                          index,
                          switches[index].label,
                          icon: switches[index].icon,
                          iconOnly: true,
                        ),
                    ],
                    values: <int>{
                      for (int index = 0; index < switches.length; index++)
                        if (switches[index].pressed == true) index,
                    },
                    onChanged: (Set<int> next) {
                      for (int index = 0; index < switches.length; index++) {
                        final bool on = switches[index].pressed == true;
                        if (next.contains(index) != on) {
                          switches[index].onPressed?.call();
                        }
                      }
                    },
                  ),
              ],
              more: frame.more,
              secondary: actions,
            ),
          ]),
        dom.div(
          classes:
              'ui-stage-body${frame.state == StageState.ready ? '' : ' is-${frame.state.name}'}',
          attributes: const <String, String>{'data-preview-frame': ''},
          <Widget>[_body()],
        ),
        if (frame.status != null)
          dom.footer(classes: 'ui-stage-status', <Widget>[
            TipLabel(
              label: Component.text(frame.status!),
              tip: frame.statusTip,
            ),
          ]),
      ],
    );
  }
}
