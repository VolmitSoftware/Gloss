/// The entity overlays surface: a sample mob in the game screen's world with
/// the document's overlay anchored above it. The header strikes and heals
/// the sample; the sample controls sheet sets its name, stats and which
/// plugins it pretends are installed. None of that reaches the server.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/entity_overlay_preview.dart';
import '../../logic/gloss_text.dart';
import '../../logic/gloss_particle_preview.dart';
import '../../mc/rigs/mc_rig.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../gloss/gloss_particle_overlay.dart';
import '../../model/gloss_entity_overlays.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/number_field.dart';
import '../../ui/sheet.dart';
import '../../ui/switch_field.dart';
import '../gloss/gloss_game_screen.dart';
import '../gloss/gloss_text_line.dart';
import '../mc/mc_asset_loader.dart';
import '../mc/mc_dom_layer.dart';
import '../mc/mc_stage_controller.dart';

class EntityOverlayView extends StatefulWidget {
  const EntityOverlayView({required this.store, super.key});

  final EditorStore store;

  @override
  State<EntityOverlayView> createState() => _EntityOverlayViewState();
}

class _EntityOverlayViewState extends State<EntityOverlayView> {
  Timer? _hitTimer;
  Timer? _animationTimer;
  final Stopwatch _clock = Stopwatch();
  String _name = 'Sample sentinel';
  double _health = 14;
  double _maxHealth = 20;
  double _attack = 3;
  double _armor = 2;
  double _distance = 6;
  double _damage = 0;
  int _stackCount = 4;
  bool _react = false;
  bool _adapt = false;
  bool _insight = false;
  bool _exclusive = false;
  bool _player = false;

  /// Clock time of the last `_strike()`; the rig's red hurt overlay shows
  /// for 500 ms after this regardless of `doc.hitHighlightMs`.
  int _hitAtMs = -1000;

  /// Whether the sample controls sheet is open.
  bool _sampleOpen = false;

  /// The game frame stands at the client's eye over the sample and does not
  /// orbit.
  late final McStageController _stage = McStageController(
    kind: McStageKind.frame,
    homePivot: const McVec3(0.5, mcCameraEyeHeight, 0.5),
    freeCamera: false,
  );

  /// The last camera and WebGL verdict this view rendered against, so a
  /// notification raised by its own scene write is told apart from one that
  /// actually moves the anchored label.
  late McCamera _lastCamera = _stage.camera;
  late bool _lastWebGl = _stage.webGlAvailable;

  /// The instant the rig is posed at. It follows the sample clock while frames
  /// are being drawn and holds its last value once they stop, so a parked
  /// stage keeps the pose it froze on.
  int _poseTimeMs = 0;

  @override
  void initState() {
    super.initState();
    component.store.addListener(_changed);
    _stage.addListener(_onStageChanged);
    _stage.spriteFor = _spriteFor;
    _clock.start();
    _syncClock();
  }

  @override
  void didUpdateWidget(covariant EntityOverlayView oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) {
      oldComponent.store.removeListener(_changed);
      component.store.addListener(_changed);
    }
  }

  @override
  void dispose() {
    _hitTimer?.cancel();
    _animationTimer?.cancel();
    _clock.stop();
    component.store.removeListener(_changed);
    _stage.removeListener(_onStageChanged);
    _stage.dispose();
    super.dispose();
  }

  void _changed() {
    _syncClock();
    if (mounted) setState(() {});
  }

  /// The camera moved, or the stage gave up on WebGL: the label anchor is
  /// this view's, so it is re-projected here. The scene and the animating
  /// flag are this view's own writes, and the controller stays quiet on an
  /// unchanged scene, so neither reaches this.
  void _onStageChanged() {
    if (!mounted) return;
    if (_lastCamera == _stage.camera && _lastWebGl == _stage.webGlAvailable) {
      return;
    }
    _lastCamera = _stage.camera;
    _lastWebGl = _stage.webGlAvailable;
    setState(() {});
  }

  /// The no-WebGL sprite for a node, off the catalogs the store holds now.
  String? _spriteFor(McSceneNode node) =>
      mcCatalogSpriteFor(component.store.catalogs, node);

  void _syncClock() {
    final GlossEntityOverlaysDoc? doc = component.store.entityOverlaysDoc;
    final bool hitFlashing =
        _damage > 0 && _clock.elapsedMilliseconds - _hitAtMs < 500;
    final bool active =
        doc != null &&
        (hitFlashing ||
            doc.particleLayers.isNotEmpty ||
            doc.show.toString().contains('time.') ||
            doc.lines.any(
              (GlossEntityOverlayLine line) =>
                  glossTextRequiresFastRefresh(line.text) ||
                  line.show.toString().contains('time.'),
            ));
    if (!active) {
      _animationTimer?.cancel();
      _animationTimer = null;
    } else {
      _animationTimer ??= Timer.periodic(const Duration(milliseconds: 50), (_) {
        if (mounted) setState(_syncClock);
      });
    }
  }

  void _strike() {
    final GlossEntityOverlaysDoc? doc = component.store.entityOverlaysDoc;
    if (doc == null) return;
    _hitTimer?.cancel();
    setState(() {
      if (_health <= 0) _health = _maxHealth;
      _damage = math.min(3, _health);
      _health -= _damage;
      _hitAtMs = _clock.elapsedMilliseconds;
    });
    _syncClock();
    _hitTimer = Timer(
      Duration(milliseconds: doc.hitHighlightMs.clamp(0, 10000)),
      () {
        if (mounted) setState(() => _damage = 0);
      },
    );
  }

  void _restore() {
    _hitTimer?.cancel();
    setState(() {
      _health = _maxHealth;
      _damage = 0;
    });
    _syncClock();
  }

  @override
  Widget build(BuildContext context) {
    GlossEntityOverlaysDoc? doc = component.store.entityOverlaysDoc;
    if (doc == null) {
      return glossGameEmpty(
        anchor: GlossGameAnchor.world,
        label: huiText('Entity overlay sample in game'),
      );
    }
    final EntityOverlaySample sample = EntityOverlaySample(
      name: _name,
      health: _health,
      maxHealth: _maxHealth,
      attack: _attack,
      armor: _armor,
      distance: _distance,
      damage: _damage,
      stackCount: _stackCount,
      react: _react,
      adapt: _adapt,
      insight: _insight,
      adaptExclusive: _exclusive,
      player: _player,
      entityType: _player ? 'PLAYER' : 'ZOMBIE',
    );
    doc = resolveEntityOverlayDocument(
      doc,
      sample,
      nowMs: _clock.elapsedMilliseconds,
      names: component.store.workspaceNames,
    );
    final EntityOverlayPreview preview = resolveEntityOverlayPreview(
      doc,
      sample,
      names: component.store.workspaceNames,
      animations: component.store.workspaceAnimations,
      emoji: component.store.workspaceEmoji,
      nowMs: _clock.elapsedMilliseconds,
    );
    final String rigId = mcRigForEntityType(sample.entityType) ?? 'zombie';
    final bool slim = McAssetLoader.loaded?.manifest.player.slim ?? false;
    final McRig rig = mcRigById(rigId, slim: slim)!;
    final bool isPlayer = rigId == 'player';
    final bool isSheep = rigId == 'sheep';
    final double hurt =
        _damage > 0 && _clock.elapsedMilliseconds - _hitAtMs < 500 ? 1.0 : 0.0;
    final bool animating =
        (_hitTimer != null || _animationTimer != null) &&
        component.store.animationsPlaying;
    if (animating) _poseTimeMs = _clock.elapsedMilliseconds;
    _stage.scene = McScene(<McSceneNode>[
      McRigNode(
        key: 'sample',
        rigId: rigId,
        textureId: isPlayer ? 'player' : rigId,
        transform: McMat4.translation(0.5, 0, 0.5),
        poseTimeMs: _poseTimeMs,
        hurt: hurt,
        capeTextureId: isPlayer ? 'player_cape' : null,
        overlayTextureId: isSheep ? 'sheep_wool' : null,
      ),
    ]);
    _stage.animating = animating;
    final List<Widget> overlay = preview.visible
        ? <Widget>[_labelAnchor(doc, preview, rig)]
        : const <Widget>[];
    // Why the overlay is hidden for this sample, else the still-world note.
    final String? status =
        preview.hiddenReason ?? (_stage.webGlAvailable ? null : _noWebGlNote());
    // The sheet is fixed over the page, so it adds nothing to the centre
    // cell's layout next to the frame.
    return Component.fragment(<Widget>[
      GlossGameScreen(
        anchor: GlossGameAnchor.world,
        label: huiText('Entity overlay sample in game'),
        actions: <RowAction>[
          RowAction(
            huiText('Strike sample'),
            _strike,
            icon: ArcaneIcon.sword(),
            iconOnly: true,
          ),
          RowAction(
            huiText('Restore health'),
            _restore,
            icon: ArcaneIcon.heartPulse(),
            iconOnly: true,
          ),
          RowAction(
            huiText('Sample controls'),
            () => setState(() => _sampleOpen = !_sampleOpen),
            icon: ArcaneIcon.slidersHorizontal(),
            iconOnly: true,
            expanded: _sampleOpen,
          ),
        ],
        status: status,
        world: _stage,
        worldOverlay: overlay,
        child: const dom.div(<Widget>[]),
      ),
      Sheet(
        open: _sampleOpen,
        title: huiText('Sample controls'),
        dense: true,
        onClose: () => setState(() => _sampleOpen = false),
        child: _sampleControls(),
      ),
    ]);
  }

  /// The overlay text anchored above the rig, in the stage's CSS-3D layer.
  Widget _labelAnchor(
    GlossEntityOverlaysDoc doc,
    EntityOverlayPreview preview,
    McRig rig,
  ) {
    final McVec3 position = McVec3(
      0.5,
      rig.heightBlocks + doc.verticalOffset.clamp(-2, 8) * 0.25,
      0.5,
    );
    return dom.div(
      classes: 'hui-mc-anchor',
      styles: dom.Styles(
        raw: <String, String>{
          'transform': mcDomAnchorTransform(
            camera: _stage.camera,
            position: position,
            billboard: McBillboardMode.center,
          ),
        },
      ),
      <Widget>[
        dom.div(
          classes: 'hui-entity-overlay-label',
          styles: dom.Styles(
            raw: <String, String>{
              // The anchor places this bottom-centred; only the document scale
              // rides on the element itself.
              'transform-origin': '50% 100%',
              'transform':
                  'scale(${doc.style.scaleX.clamp(0.01, 64)}, ${doc.style.scaleY.clamp(0.01, 64)})',
              'text-align': doc.style.textAlignment,
              'padding':
                  '${doc.box.enabled ? doc.box.padding.clamp(0, 64) * 2 : 0}px',
              'border': doc.box.enabled
                  ? '${doc.box.borderWidth.clamp(0, 16) * 2}px solid ${_argb(doc.box.borderArgb)}'
                  : 'none',
              'background': doc.box.enabled
                  ? _argb(doc.box.backgroundArgb)
                  : 'transparent',
              'background-clip': 'padding-box',
            },
          ),
          <Widget>[
            GlossParticleOverlay(
              layers: doc.particleLayers,
              pixelsPerBlock: 80,
              tick: _clock.elapsedMilliseconds ~/ 50,
              renderedText: preview.particleText,
              textScale: 1,
              scopeBounds: <String, List<GlossParticleRect>>{
                'projection': <GlossParticleRect>[
                  glossParticleTextBounds(preview.particleText.text, 1),
                ],
              },
            ),
            dom.div(
              classes: 'hui-entity-overlay-text',
              styles: dom.Styles(
                raw: <String, String>{
                  'background': _argb(doc.style.backgroundArgb),
                  'opacity': (doc.style.textOpacity.clamp(0, 255) / 255)
                      .toString(),
                  'max-width': '${doc.style.lineWidth.clamp(1, 16384) * 2}px',
                  'text-shadow': doc.style.shadow
                      ? '2px 2px rgba(0,0,0,.65)'
                      : 'none',
                },
              ),
              <Widget>[
                for (final GlossLineRender line in preview.rows)
                  GlossTextLine(render: line),
              ],
            ),
          ],
        ),
      ],
    );
  }

  /// The status line when the stage fell back to the still.
  String _noWebGlNote() => huiText(
    'No WebGL2 in this browser: the world is a still and models are '
    'sprites',
  );

  /// The sample's name, stats and the plugins it pretends are installed.
  /// Editing a stat clears a pending hit so the label shows the new value.
  Widget _sampleControls() => Component.fragment(<Widget>[
    ArcaneFieldDescription(
      huiText(
        'Use the sample controls to compare these states. Sample controls do not change server settings.',
      ),
    ),
    Field(
      label: huiText('Sample name'),
      forId: 'entity-overlay-sample-name',
      child: TextInput(
        id: 'entity-overlay-sample-name',
        value: _name,
        size: controlSize(Density.dense),
        fullWidth: true,
        onChanged: (String value) => setState(() => _name = value),
      ),
    ),
    _number(
      'Sample health',
      'health',
      _health,
      (double value) => _health = value.clamp(0, _maxHealth),
    ),
    _number('Sample max health', 'max-health', _maxHealth, (double value) {
      _maxHealth = math.max(1, value);
      _health = math.min(_health, _maxHealth);
    }),
    _number(
      'Sample attack',
      'attack',
      _attack,
      (double value) => _attack = math.max(0, value),
    ),
    _number(
      'Sample armor',
      'armor',
      _armor,
      (double value) => _armor = math.max(0, value),
    ),
    _number(
      'Sample distance',
      'distance',
      _distance,
      (double value) => _distance = math.max(0, value),
    ),
    _toggle('Player sample', _player, (bool on) => _player = on),
    _toggle('React installed', _react, (bool on) => _react = on),
    if (_react)
      _number(
        'Sample stack count',
        'stack-count',
        _stackCount.toDouble(),
        (double value) => _stackCount = math.max(1, value.round()),
      ),
    _toggle('Adapt installed', _adapt, (bool on) => _adapt = on),
    if (_adapt) ...<Widget>[
      _toggle('Insight active', _insight, (bool on) => _insight = on),
      _toggle('Adapt exclusive', _exclusive, (bool on) => _exclusive = on),
    ],
  ]);

  Widget _number(
    String label,
    String key,
    double value,
    void Function(double) edit,
  ) => Field(
    label: huiText(label),
    forId: 'entity-overlay-sample-$key',
    child: NumberField(
      id: 'entity-overlay-sample-$key',
      value: value,
      decimals: 1,
      step: 1,
      onChanged: (double next) => setState(() {
        _damage = 0;
        edit(next);
      }),
    ),
  );

  Widget _toggle(String label, bool value, void Function(bool) edit) =>
      SwitchField(
        label: huiText(label),
        value: value,
        onChanged: (bool next) => setState(() => edit(next)),
      );

  static String _argb(String value) {
    if (!RegExp(r'^#[a-fA-F0-9]{8}$').hasMatch(value)) return 'transparent';
    return '#${value.substring(3)}${value.substring(1, 3)}';
  }
}
