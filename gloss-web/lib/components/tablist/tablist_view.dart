/// The tablist surface: a Minecraft tab-screen mock — header, the player
/// grid with cosmetic ping bars, footer.
///
/// Fidelity notes, all from `TablistService.java`: header, footer and every
/// list name render through the text pipeline per viewer (`renderSafe`), so
/// `|animation.<id>|` references play here through the workspace-scoped
/// resolver; list-name resolution is `chooseListName` — `_op` first for
/// operators, then the primary group, then `default`, then the literal
/// `$player` fallback — mirrored in `logic/tablist_selection.dart`; a blank
/// chosen template resets to the vanilla name. With `useHeaderFooter` off
/// the header and footer rows drop out, and with `groupListNames` off every
/// name shows vanilla. The ping bars and the filler players are cosmetic —
/// the client fills the grid, never the plugin.
///
/// The tab screen mounts into the shared game-screen frame as the top-centre
/// overlay the tab key raises, scaled down as one picture when the screen is
/// too small for it.
///
/// Owns a playback clock while any rendered text plays an animation and the
/// store's animations toggle is on. Mounted only while the tablist view is
/// active.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_text.dart';
import '../../logic/tablist_selection.dart';
import '../../logic/tablist_layout_preview.dart';
import '../../logic/preview_expr.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../scoreboard/scoreboard_selection.dart';
import '../gloss/gloss_game_screen.dart';
import '../../ui/action_row.dart';
import '../gloss/gloss_text_line.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// Animation repaint period, matching the hologram stage.
const Duration _tickPeriod = Duration(milliseconds: 50);

/// One mock player row.
typedef _MockPlayer = ({String name, String? group, bool op, int pingBars});

const List<_MockPlayer> _players = <_MockPlayer>[
  (name: 'Cyberpwn', group: 'owner', op: true, pingBars: 5),
  (name: 'Magic_Psycho', group: 'developer', op: false, pingBars: 5),
  (name: 'SwiftSwamp', group: 'moderator', op: false, pingBars: 4),
  (name: 'Puretie', group: 'vip', op: false, pingBars: 5),
  (name: 'AgentCooper', group: null, op: false, pingBars: 5),
  (name: 'LauraPalmer', group: null, op: false, pingBars: 4),
  (name: 'TheLogLady', group: null, op: false, pingBars: 3),
  (name: 'BobbyBriggs', group: null, op: false, pingBars: 5),
  (name: 'AudreyHorne', group: null, op: false, pingBars: 2),
  (name: 'DeputyHawk', group: null, op: false, pingBars: 4),
  (name: 'ShellyJohnson', group: null, op: false, pingBars: 5),
  (name: 'BigEdHurley', group: null, op: false, pingBars: 3),
  (name: 'NadineHurley', group: null, op: false, pingBars: 4),
  (name: 'LelandPalmer', group: null, op: false, pingBars: 2),
  (name: 'DrJacoby', group: null, op: false, pingBars: 5),
  (name: 'GordonCole', group: null, op: false, pingBars: 3),
  (name: 'DianeEvans', group: null, op: false, pingBars: 4),
  (name: 'JosiePackard', group: null, op: false, pingBars: 5),
  (name: 'PeteMartell', group: null, op: false, pingBars: 2),
  (name: 'CatherineM', group: null, op: false, pingBars: 3),
  (name: 'AndyBrennan', group: null, op: false, pingBars: 5),
  (name: 'LucyMoran', group: null, op: false, pingBars: 4),
  (name: 'DonnaHayward', group: null, op: false, pingBars: 3),
  (name: 'JamesHurley', group: null, op: false, pingBars: 5),
  (name: 'MaddyFerguson', group: null, op: false, pingBars: 4),
  (name: 'WindomEarle', group: null, op: false, pingBars: 2),
  (name: 'TheGiant', group: null, op: false, pingBars: 5),
  (name: 'MrsTremond', group: null, op: false, pingBars: 3),
  (name: 'BenHorne', group: null, op: false, pingBars: 4),
  (name: 'NormaJennings', group: null, op: false, pingBars: 5),
  (name: 'LeoJohnson', group: null, op: false, pingBars: 3),
];

class TablistView extends StatefulWidget {
  const TablistView({required this.store, super.key});

  final EditorStore store;

  @override
  State<TablistView> createState() => _TablistViewState();
}

class _TablistViewState extends State<TablistView> {
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
  void didUpdateWidget(covariant TablistView oldComponent) {
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

  bool _isAnimated(GlossTablistDoc doc, GlossAnimationResolver animations) {
    final List<String> text = <String>[
      doc.headerFooter.presentation.header,
      doc.headerFooter.presentation.footer,
      doc.listNames.presentation.format,
      for (final GlossTablistHeaderFooterVariant variant
          in doc.headerFooter.variants) ...<String>[
        variant.presentation.header,
        variant.presentation.footer,
      ],
      for (final GlossTablistListNameVariant variant in doc.listNames.variants)
        variant.presentation.format,
    ];
    for (final GlossTabPresentation presentation in <GlossTabPresentation>[
      if (doc.layout != null) doc.layout!,
      for (final GlossTabLayoutVariant variant
          in doc.layout?.variants ?? const <GlossTabLayoutVariant>[])
        variant.presentation,
    ]) {
      text.addAll(presentation.slots.map((GlossTabSlot slot) => slot.text));
      for (final GlossTabSection section in presentation.sections) {
        text.add(section.overflowFormat);
        if (section.format != null) text.add(section.format!);
      }
    }
    for (final String value in text) {
      if (renderGlossLine(value, animations: animations).isAnimated) {
        return true;
      }
    }
    return false;
  }

  /// The rendered list name for one mock player: `chooseListName`, token
  /// substitution, then the pipeline. Vanilla (plain name) when
  /// groupListNames is off or the chosen template is blank.
  String _listNameRaw(GlossTablistDoc doc, _MockPlayer player) {
    if (!glossTablistListNamesVisible(
      doc,
      _conditionContext(
        subjectName: player.name,
        subjectGroup: player.group,
        subjectOp: player.op,
      ),
      nowMs: DateTime.now().millisecondsSinceEpoch,
    )) {
      return player.name;
    }
    final GlossTablistListNamePresentation presentation =
        glossResolveTablistListName(
          doc,
          _conditionContext(
            subjectName: player.name,
            subjectGroup: player.group,
            subjectOp: player.op,
          ),
        );
    if (presentation.format.trim().isEmpty) return player.name;
    return glossTablistSubstituteTokens(
      presentation.format,
      player.name,
      player.group,
    );
  }

  @override
  Widget build(BuildContext context) {
    final GlossTablistDoc? doc = _store.tablistDoc;
    if (doc == null) {
      _syncTicker(false);
      return glossGameEmpty(
        anchor: GlossGameAnchor.tabOverlay,
        label: huiText('Tab list in game'),
      );
    }
    final GlossAnimationResolver animations = _store.workspaceAnimations;
    final GlossEmojiResolver emoji = _store.workspaceEmoji;
    _syncTicker(
      _isAnimated(doc, animations) ||
          doc.extras['show'] is String ||
          doc.headerFooter.extras['show'] is String ||
          doc.listNames.extras['show'] is String ||
          (doc.layout?.enabled ?? false),
    );
    final int nowMs = DateTime.now().millisecondsSinceEpoch;
    final GlossConditionContext viewerContext = _conditionContext();
    final GlossTablistHeaderFooterPresentation headerFooter =
        glossResolveTablistHeaderFooter(doc, viewerContext);

    // The header and the footer are one component each, so a colour or format
    // runs on across their lines.
    List<Widget> pipelineLines(String text) => <Widget>[
      for (final GlossLineRender line in glossDisplayLines(
        renderGlossLine(
          text,
          animations: animations,
          emoji: emoji,
          nowMs: nowMs,
        ),
      ))
        dom.div(classes: 'hui-tablist-pipeline-line', <Widget>[
          GlossTextLine(render: line),
        ]),
    ];

    final GlossTabPresentation? layout = glossResolveTabLayout(
      doc.layout,
      viewerContext,
      nowMs: nowMs,
      documentShow: doc.extras['show'],
    );
    final bool layoutVisible = layout != null;
    final List<_MockPlayer> listed = List<_MockPlayer>.of(_players);
    final Map<String, double> weights = <String, double>{};
    if (layoutVisible) {
      final Object? sort = doc.extras['sort'];
      if (sort is Map && sort['enabled'] == true && sort['weight'] is String) {
        try {
          final PExpr expression = parsePreviewExpr(sort['weight'] as String);
          for (final _MockPlayer player in listed) {
            weights[player.name] = evalNumber(
              expression,
              _conditionContext(
                subjectName: player.name,
                subjectGroup: player.group,
                subjectOp: player.op,
              ),
            );
          }
        } on PExprException {
          weights.clear();
        }
      }
      listed.sort((_MockPlayer a, _MockPlayer b) {
        final int order = (weights[b.name] ?? 0).compareTo(
          weights[a.name] ?? 0,
        );
        return order != 0
            ? order
            : a.name.toLowerCase().compareTo(b.name.toLowerCase());
      });
    }
    final List<GlossTabPreviewCell> playerCells = <GlossTabPreviewCell>[
      for (final _MockPlayer player in listed)
        GlossTabPreviewCell(_listNameRaw(doc, player), player.pingBars),
    ];
    final List<GlossTabPreviewCell> cells = layoutVisible
        ? glossTabLayoutCells(
            layout,
            <GlossTabPreviewPlayer>[
              for (final _MockPlayer player in listed)
                GlossTabPreviewPlayer(
                  name: player.name,
                  group: player.group ?? '',
                  cell: GlossTabPreviewCell(
                    _listNameRaw(doc, player),
                    player.pingBars,
                  ),
                  order: weights[player.name] ?? 0,
                  scope: _conditionContext(
                    subjectName: player.name,
                    subjectGroup: player.group,
                    subjectOp: player.op,
                  ),
                ),
            ],
            viewerScope: viewerContext,
            nowMs: nowMs,
          )
        : playerCells;
    final Widget screen = dom.div(classes: 'hui-tablist-screen', <Widget>[
      if (glossTablistHeaderFooterVisible(doc, viewerContext, nowMs: nowMs))
        dom.div(
          classes: 'hui-tablist-header',
          pipelineLines(headerFooter.header),
        ),
      dom.div(
        classes: 'hui-tablist-grid',
        styles: layoutVisible
            ? dom.Styles(
                raw: <String, String>{
                  'grid-auto-flow': 'column',
                  'grid-template-rows':
                      'repeat(${layout.rows.clamp(1, 20)}, auto)',
                  'grid-template-columns':
                      'repeat(${layout.columns.clamp(1, 4)}, minmax(0, 1fr))',
                },
              )
            : null,
        <Widget>[
          for (final GlossTabPreviewCell cell in cells)
            dom.div(classes: 'hui-tablist-row', <Widget>[
              const dom.span(classes: 'hui-tablist-row-face', <Widget>[]),
              dom.span(classes: 'hui-tablist-row-name', <Widget>[
                GlossTextLine(
                  render: renderGlossLine(
                    cell.text,
                    animations: animations,
                    emoji: emoji,
                    nowMs: nowMs,
                    // Layout slots are sent as parsed components; plain list
                    // names go through the legacy-only Bukkit setter.
                    richText: layoutVisible,
                  ),
                ),
              ]),
              dom.span(classes: 'hui-tablist-row-ping', <Widget>[
                for (int bar = 0; bar < 5; bar++)
                  dom.span(
                    classes:
                        'hui-tablist-ping-bar'
                        '${bar < cell.pingBars ? ' is-filled' : ''}',
                    const <Widget>[],
                  ),
              ]),
            ]),
        ],
      ),
      if (glossTablistHeaderFooterVisible(doc, viewerContext, nowMs: nowMs))
        dom.div(
          classes: 'hui-tablist-footer',
          pipelineLines(headerFooter.footer),
        ),
    ]);

    return GlossGameScreen(
      anchor: GlossGameAnchor.tabOverlay,
      label: huiText('Tab list in game'),
      actions: <RowAction>[_playPause()],
      child: screen,
    );
  }

  /// The animation transport, the same shape the hologram stage uses. It
  /// drives the store's workspace-wide toggle, so pausing here pauses every
  /// surface that references an animation.
  RowAction _playPause() => RowAction(
    _store.animationsPlaying
        ? huiText('Pause animations')
        : huiText('Play animations'),
    () => _store.animationsPlaying = !_store.animationsPlaying,
    icon: _store.animationsPlaying ? ArcaneIcon.pause() : ArcaneIcon.play(),
    iconOnly: true,
  );

  GlossConditionContext _conditionContext({
    String subjectName = 'Cyberpwn',
    String? subjectGroup,
    bool subjectOp = false,
  }) {
    const double maxHealth = 20;
    return GlossConditionContext(
      variables: <String, Object?>{
        'viewer.name': 'Cyberpwn',
        'viewer.health': _health,
        'viewer.maxHealth': maxHealth,
        'viewer.healthPercent': _health * 100 / maxHealth,
        'viewer.world': _world,
        'world.name': _world,
        'viewer.op': false,
        'viewer.bedrock': false,
        'viewer.ping': 42.0,
        'subject.name': subjectName,
        'subject.op': subjectOp,
        'subject.world': _world,
        'server.online': 30.0,
        'server.maxPlayers': 100.0,
        'server.tps': 20.0,
      },
      groupsByRole: <String, Set<String>>{
        'subject': <String>{
          if (subjectGroup != null && subjectGroup.isNotEmpty)
            subjectGroup.toLowerCase(),
        },
      },
      metrics: <String, double>{'react.tick-ms': 50},
    );
  }
}
