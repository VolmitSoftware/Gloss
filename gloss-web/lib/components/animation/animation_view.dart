/// The animation player surface: the current frame large, transport controls,
/// a frame scrubber and the frame strip.
///
/// Playing mirrors the server exactly — `AnimationClip.frameAt` over the wall
/// clock, so what this shows at a given millisecond is what every hologram or
/// scoreboard referencing `|animation.<id>|` shows in game. Pause freezes,
/// the scrubber addresses frames directly, and resume rejoins the live
/// timeline. Playback state lives in `logic/gloss_animation_player.dart`
/// under an injectable clock; this file owns only the repaint timer and DOM.
///
/// The current frame mounts alone into the shared game-screen frame: an
/// animation has no HUD slot of its own, so it is shown in-world at the size
/// a hologram would draw it, with the transport in the frame's header.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_animation_player.dart';
import '../../logic/gloss_text.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../gloss/gloss_game_screen.dart';
import '../gloss/gloss_text_line.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class AnimationView extends StatefulWidget {
  const AnimationView({required this.store, super.key});

  final EditorStore store;

  @override
  State<AnimationView> createState() => _AnimationViewState();
}

class _AnimationViewState extends State<AnimationView> {
  final GlossAnimationPlayer _player = GlossAnimationPlayer();
  Timer? _ticker;

  EditorStore get _store => component.store;

  @override
  void initState() {
    super.initState();
    _store.addListener(_onStoreChanged);
  }

  @override
  void didUpdateWidget(covariant AnimationView oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) {
      oldComponent.store.removeListener(_onStoreChanged);
      component.store.addListener(_onStoreChanged);
    }
  }

  @override
  void dispose() {
    _store.removeListener(_onStoreChanged);
    _ticker?.cancel();
    super.dispose();
  }

  void _onStoreChanged() {
    if (mounted) setState(() {});
  }

  /// Repaints at the clip cadence, floored so a 1 ms clip does not spin a
  /// millisecond timer — frames faster than the screen can show still land
  /// on the right index every repaint because the index is clock-derived.
  void _syncTicker(GlossAnimationDoc doc) {
    final bool expressionDriven = doc.frames.any(glossMenuTextNeedsRefresh);
    final bool wanted =
        _player.playing &&
        _store.animationsPlaying &&
        (doc.frames.length > 1 || expressionDriven);
    if (!wanted) {
      _ticker?.cancel();
      _ticker = null;
      return;
    }
    final int period = expressionDriven
        ? 100
        : doc.effectiveFrameIntervalMs.clamp(33, 1000);
    if (_ticker != null && _tickerPeriodMs == period) return;
    _ticker?.cancel();
    _tickerPeriodMs = period;
    _ticker = Timer.periodic(Duration(milliseconds: period), (Timer _) {
      if (mounted) setState(() {});
    });
  }

  int _tickerPeriodMs = 0;

  @override
  Widget build(BuildContext context) {
    final GlossAnimationDoc? doc = _store.animationDoc;
    if (doc == null) {
      _ticker?.cancel();
      _ticker = null;
      return glossGameEmpty(
        anchor: GlossGameAnchor.world,
        label: huiText('Animation frame in game'),
      );
    }
    _syncTicker(doc);
    final String id = _store.menuId;
    final ({int nowMs, int frameIndex, String frameText}) sample = _player
        .sample(doc, id);
    final int index = sample.frameIndex;
    final String frame = sample.frameText;

    final Widget currentFrame = dom.div(
      classes: 'hui-animation-frame-large',
      <Widget>[
        GlossTextLine(
          render: renderGlossAnimationFramePreview(
            frame,
            emoji: _store.workspaceEmoji,
            nowMs: sample.nowMs,
          ),
        ),
      ],
    );

    return GlossGameScreen(
      anchor: GlossGameAnchor.world,
      label: huiText('Animation frame in game'),
      fit: true,
      actions: <RowAction>[
        RowAction(
          huiText('Previous frame'),
          () => setState(() => _player.step(-1, doc, id)),
          icon: ArcaneIcon.skipBack(),
          iconOnly: true,
        ),
        RowAction(
          _player.playing ? huiText('Pause') : huiText('Play'),
          () => setState(() => _player.toggle(doc, id)),
          icon: _player.playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          iconOnly: true,
        ),
        RowAction(
          huiText('Next frame'),
          () => setState(() => _player.step(1, doc, id)),
          icon: ArcaneIcon.skipForward(),
          iconOnly: true,
        ),
      ],
      status: doc.frames.isEmpty
          ? huiText('No frames')
          : huiText(
              'Frame {current} of {total} · {interval} ms · '
              '{mode}{paused}',
              <String, Object?>{
                'current': index + 1,
                'total': doc.frames.length,
                'interval': doc.effectiveFrameIntervalMs,
                'mode':
                    doc.normalizedMode ??
                    huiText('{mode} (invalid)', <String, Object?>{
                      'mode': doc.mode,
                    }),
                'paused': _player.playing ? '' : huiText(' · paused'),
              },
            ),
      child: currentFrame,
    );
  }
}
