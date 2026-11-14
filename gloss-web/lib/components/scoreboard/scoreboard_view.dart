/// The scoreboard surface: a vanilla-style sidebar mock, right-anchored the
/// way the client draws it.
///
/// The title and rows render through Gloss's text pipeline as complete modern
/// components without character truncation. Empty titles stay blank. At most
/// 15 visible lines reach the client, with each row's authored score format.
///
/// The sidebar mounts into the shared game-screen frame at the right edge,
/// where the client draws it, scaled down as one picture when the screen is
/// too small for it.
///
/// Owns a playback clock while any rendered line (or the title) plays an
/// animation and the store's animations toggle is on. Mounted only while the
/// scoreboard view is active.
library;

import '../../logic/gloss_show.dart';
import '../../logic/scoreboard_layout.dart';

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_text.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../gloss/gloss_game_screen.dart';
import '../../ui/action_row.dart';
import '../../ui/mode.dart';
import '../gloss/gloss_text_line.dart';
import 'scoreboard_selection.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// Animation repaint period, matching the hologram stage.
const Duration _tickPeriod = Duration(milliseconds: 50);

class ScoreboardView extends StatefulWidget {
  const ScoreboardView({required this.store, super.key});

  final EditorStore store;

  @override
  State<ScoreboardView> createState() => _ScoreboardViewState();
}

class _ScoreboardViewState extends State<ScoreboardView> {
  /// The sample viewer every condition is resolved for.
  static const String _world = 'world';
  static const double _health = 20;

  Timer? _ticker;

  EditorStore get _store => component.store;

  @override
  void initState() {
    super.initState();
    _store.addListener(_onStoreChanged);
  }

  @override
  void didUpdateWidget(covariant ScoreboardView oldComponent) {
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

  void _syncTicker(bool animated) {
    final bool wanted = animated && _store.animationsPlaying;
    if (wanted && _ticker == null) {
      _ticker = Timer.periodic(_tickPeriod, (Timer _) {
        if (mounted) setState(() {});
      });
    } else if (!wanted && _ticker != null) {
      _ticker?.cancel();
      _ticker = null;
    }
  }

  bool _isAnimated(
    GlossScoreboardPresentation presentation,
    GlossAnimationResolver animations,
  ) {
    final Object? layout = presentation.extras['layout'];
    if (layout is Map &&
        layout['pages'] is List &&
        (layout['pages']! as List).isNotEmpty) {
      return true;
    }
    if (presentation.lines.any(
      (GlossScoreboardLine row) => row.show is String || row.section != null,
    )) {
      return true;
    }
    if (renderGlossLine(
      _effectiveTitle(presentation),
      animations: animations,
    ).isAnimated) {
      return true;
    }
    final int rendered = presentation.lines.length > glossBoardMaxLines
        ? glossBoardMaxLines
        : presentation.lines.length;
    for (int index = 0; index < rendered; index++) {
      if (renderGlossLine(
        presentation.lines[index].value ?? '',
        animations: animations,
      ).isAnimated) {
        return true;
      }
      if (renderGlossLine(
        presentation.lines[index].text,
        animations: animations,
      ).isAnimated) {
        return true;
      }
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    final GlossScoreboardDoc? doc = _store.scoreboardDoc;
    if (doc == null) {
      _syncTicker(false);
      return glossGameEmpty(
        anchor: GlossGameAnchor.sidebar,
        label: huiText('Scoreboard in game'),
      );
    }
    final GlossAnimationResolver animations = _store.workspaceAnimations;
    final GlossEmojiResolver emoji = _store.workspaceEmoji;
    final GlossConditionContext conditionContext = _conditionContext();
    final GlossScoreboardPresentation authored =
        glossResolveScoreboardPresentation(doc, conditionContext);
    final int nowMs = DateTime.now().millisecondsSinceEpoch;
    final GlossScoreboardPresentation presentation =
        glossResolveScoreboardLayout(authored, conditionContext, nowMs);
    final String? variantId = glossResolveScoreboardVariantId(
      doc,
      conditionContext,
    );
    _syncTicker(
      _isAnimated(authored, animations) || doc.extras['show'] is String,
    );
    final int rendered = presentation.lines.length > glossBoardMaxLines
        ? glossBoardMaxLines
        : presentation.lines.length;
    final int clipped = presentation.lines.length - rendered;
    final String title = _effectiveTitle(presentation);

    final Widget sidebar = dom.div(
      styles: dom.Styles(
        raw: <String, String>{
          if (!glossShowMatches(
            doc.extras['show'],
            scope: conditionContext,
            nowMs: nowMs,
          ))
            'visibility': 'hidden',
        },
      ),
      classes: 'hui-scoreboard-sidebar',
      <Widget>[
        dom.div(classes: 'hui-scoreboard-title', <Widget>[
          GlossTextLine(
            render: renderGlossScoreboardTitle(
              title,
              animations: animations,
              emoji: emoji,
              nowMs: nowMs,
            ),
          ),
        ]),
        for (int index = 0; index < rendered; index++)
          dom.div(classes: 'hui-scoreboard-row', <Widget>[
            dom.span(<Widget>[
              GlossTextLine(
                render: renderGlossScoreboardLine(
                  presentation.lines[index].text,
                  animations: animations,
                  emoji: emoji,
                  nowMs: nowMs,
                ),
              ),
            ]),
            if (glossScoreboardValue(
                  presentation.lines[index],
                  index,
                  presentation.hideNumbers,
                ) !=
                null)
              dom.span(classes: 'hui-scoreboard-score', <Widget>[
                GlossTextLine(
                  render: renderGlossScoreboardLine(
                    glossScoreboardValue(
                      presentation.lines[index],
                      index,
                      presentation.hideNumbers,
                    )!,
                    animations: animations,
                    emoji: emoji,
                    nowMs: nowMs,
                  ),
                ),
              ]),
          ]),
      ],
    );

    return GlossGameScreen(
      anchor: GlossGameAnchor.sidebar,
      label: huiText('Scoreboard in game'),
      actions: <RowAction>[_playPause()],
      status: _status(ModeScope.advanced(context) ? variantId : null, clipped),
      child: sidebar,
    );
  }

  /// The animation transport. It drives the store's workspace-wide toggle,
  /// so pausing here pauses every surface that references an animation.
  RowAction _playPause() => RowAction(
    _store.animationsPlaying
        ? huiText('Pause animations')
        : huiText('Play animations'),
    () => _store.animationsPlaying = !_store.animationsPlaying,
    icon: _store.animationsPlaying ? ArcaneIcon.pause() : ArcaneIcon.play(),
    iconOnly: true,
  );

  /// What one look at the sidebar cannot tell: the variant shown (Advanced)
  /// and the lines past the client's cap. Null when there is nothing to say.
  String? _status(String? variantId, int clipped) {
    final List<String> parts = <String>[
      if (variantId != null)
        huiText('variant: {id}', <String, Object?>{'id': variantId}),
      if (clipped > 0)
        huiPlural(
          'scoreboard.readout.clipped-lines',
          clipped,
          oneEnglish: '{count} line past the 15-line render cap',
          otherEnglish: '{count} lines past the 15-line render cap',
        ),
    ];
    return parts.isEmpty ? null : parts.join(' · ');
  }

  String _effectiveTitle(GlossScoreboardPresentation presentation) =>
      presentation.title;

  GlossConditionContext _conditionContext() {
    const double maxHealth = 20;
    return GlossConditionContext(
      variables: <String, Object?>{
        'viewer.health': _health,
        'viewer.maxHealth': maxHealth,
        'viewer.healthPercent': _health * 100 / maxHealth,
        'viewer.world': _world,
        'world.name': _world,
        'world.environment': _world.contains('nether')
            ? 'nether'
            : _world.contains('end')
            ? 'the_end'
            : 'normal',
        'viewer.op': false,
        'viewer.ping': 42.0,
        'viewer.gameMode': 'survival',
        'subject.health': _health,
        'subject.healthPercent': _health * 100 / maxHealth,
        'subject.world': _world,
        'subject.op': false,
        'server.online': 12.0,
        'server.maxPlayers': 100.0,
        'server.tps': 20.0,
      },
      groups: const <String>{},
      permissions: const <String>{},
      metrics: <String, double>{'react.tick-ms': 50},
    );
  }
}
