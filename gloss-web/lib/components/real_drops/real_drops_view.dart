/// The drop stage: a dropped stack thrown forward, tumbling, bouncing and
/// settling under the settings the document actually carries.
///
/// What it draws is the plugin's own presentation model — `RealDropModel`,
/// ported in `logic/real_drop_model.dart` and driven by
/// `logic/real_drop_stage.dart`. The number of item displays comes from the
/// stack size, their places from the offset table, their size from the scale
/// family the material belongs to, their pose from the configured tumble
/// rates and the settled landing mode, their lift off the ground from the
/// authored Y offsets, and the label from the presentation's `labels.format`,
/// `labels.names` and `labels.useItemDisplayNames`. Changing any field in the
/// inspector changes the stage the way it changes the server.
///
/// Left alone, the stage rotates through one stack per model family (a cube,
/// a flat item and a slab), changing on every completed drop, so a viewer sees
/// all three shapes without touching anything. The Next sample stack action
/// steps out of that rotation and through the whole themed sample table
/// instead, and stays on whatever it lands on until it is pressed again.
///
/// The two optional blocks both reach the stage, and the difference between
/// them is the one thing this surface must never blur:
///
///  * **`physics`** moves the item. Its four numbers are applied to the stage's
///    own arc, so gravity, bounce, buoyancy and drag visibly change where the
///    stack goes and when it lands — with the plugin's constants, on the
///    stage's trajectory.
///  * **`script`** moves the picture. `offset` shifts the model, `rotation`
///    composes onto the pose, `scale` squashes it per axis, `glow` traces an
///    outline and `visible` stops drawing it. None of that moves the item: in
///    game the pickup radius stays where Minecraft put it, and a display hidden
///    by `visible` is still a drop a player can walk over.
///
/// Four things are the stage's own approximations:
///
///  * **Ballistics.** Minecraft's `Item` entity falls and bounces; Gloss only
///    reacts to it. The arc here is a simple one, sized to show a tumble and a
///    settle, and thrown toward the camera the way `Player.drop` throws a
///    stack — at a distance the stage can frame rather than the five blocks
///    vanilla's own numbers would cover. The `physics` block changes that arc,
///    not the server's.
///  * **The models.** The stack is drawn by [McStage] out of the client's own
///    pack: a placeable sample is its block model, everything else is the item
///    model at the `fixed` pose. Animated textures show their first frame, and
///    a material the resolver has no geometry for is drawn as its sprite.
///  * **The environment the script reads.** `inWater` and `inLava` are
///    false and both light levels are 15. There is no world under this stage
///    to read them off; see [DropStageEnvironment], which is where those
///    values are decided and stated.
///  * **The glow outline.** `script.glow` reaches the renderer as the node's
///    `glowArgb`; how close that lands to the client's silhouette-through-
///    blocks outline is the renderer's business, not this view's.
///
/// Poses are not eased between updates: the stage redraws once per server
/// update, so a coarse `limits.updateIntervalTicks` reads as coarse here the
/// way it does in game.
///
/// The camera belongs to [McStage]. This view owns only the controller it
/// hands to the game frame: the scene each frame, the home framing, and the
/// label anchored in the stage's CSS-3D layer. The controller stands at the
/// client's own eye over the landing spot with `freeCamera: false`: the frame
/// stands in for the client's own view, and a player does not get to fly.
///
/// A material the pack resolves to a sprite fallback (`trident`, `shield`)
/// reaches the stage as catalog-sprite billboards the size of the model,
/// prefetched into the pack once so the renderer can bind them.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/showcase_flavor.dart';
import '../../logic/gloss_text.dart';
import '../../logic/gloss_particle_preview.dart';
import '../../logic/gloss_particle_text.dart';
import '../../logic/gloss_show.dart';
import '../../logic/real_drop_scene_nodes.dart';
import '../../logic/real_drop_selection.dart';
import '../../logic/real_drop_stage.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../model/model.dart';
import '../../preview/projection.dart' show huiPreviewPxPerBlock;
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../gloss/gloss_game_screen.dart';
import '../gloss/gloss_display_text.dart';
import '../gloss/gloss_particle_overlay.dart';
import '../gloss/gloss_text_line.dart';
import '../mc/mc_asset_loader.dart';
import '../mc/mc_dom_layer.dart';
import '../mc/mc_stage_controller.dart';
import '../scoreboard/scoreboard_selection.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class RealDropsView extends StatefulWidget {
  const RealDropsView({required this.store, super.key});

  final EditorStore store;

  @override
  State<RealDropsView> createState() => _RealDropsViewState();
}

class _RealDropsViewState extends State<RealDropsView> {
  /// The world under the stack and the camera over it, at the client's eye
  /// over the landing spot, [dropStageThrowBlocks] forward of the throw.
  late final McStageController _stage = McStageController(
    kind: McStageKind.frame,
    homePivot: const McVec3(0.5, mcCameraEyeHeight, 0.5 + dropStageThrowBlocks),
    freeCamera: false,
  );

  /// Where the stack is thrown from: the world's spawn block, ground level.
  static const McVec3 _origin = McVec3(0.5, 0, 0.5);

  /// The last camera and WebGL verdict this view rendered against, so a
  /// notification raised by its own scene write is told apart from one that
  /// actually moves the anchored overlay.
  late McCamera _lastCamera = _stage.camera;
  late bool _lastWebGl = _stage.webGlAvailable;

  Timer? _ticker;

  /// The period [_ticker] is running at, so a document change that moves the
  /// update interval is noticed.
  int _tickerPeriodMs = 0;
  bool _playing = true;

  /// The workspace animations toggle, mirrored so a flip is noticed and the
  /// clock held or released on it the way the local pause does.
  late bool _workspaceAnimationsPlaying;

  /// The preview clock: wall time minus this offset. Pausing captures the
  /// instant; resuming re-derives the offset so playback continues from it.
  int _clockOffsetMs = 0;
  int _heldMs = 0;

  /// The sample stack the shuffle button has pinned, or null while the stage
  /// rotates through one stack per model family on its own.
  int? _pinned;

  /// Timeline memo, rebuilt when the document or the stack changes.
  DropStageTimeline? _timeline;
  GlossNamesCatalog? _timelineNames;
  int _timelineRevision = -1;
  ShowcaseDrop? _timelineDrop;

  EditorStore get _store => component.store;

  @override
  void initState() {
    super.initState();
    _clockOffsetMs = DateTime.now().millisecondsSinceEpoch;
    _workspaceAnimationsPlaying = _store.animationsPlaying;
    _store.addListener(_onStoreChanged);
    _stage.addListener(_onStageChanged);
    _stage.spriteFor = _spriteFor;
  }

  @override
  void didUpdateWidget(covariant RealDropsView oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (!identical(oldComponent.store, component.store)) {
      oldComponent.store.removeListener(_onStoreChanged);
      component.store.addListener(_onStoreChanged);
      _workspaceAnimationsPlaying = _store.animationsPlaying;
    }
  }

  @override
  void dispose() {
    _store.removeListener(_onStoreChanged);
    _stage.removeListener(_onStageChanged);
    _ticker?.cancel();
    super.dispose();
  }

  /// The workspace toggle holds and releases the clock the way the local
  /// pause does, so a paused workspace draws no frames and resumes where it
  /// stopped.
  void _onStoreChanged() {
    final bool animationsPlaying = _store.animationsPlaying;
    if (animationsPlaying != _workspaceAnimationsPlaying) {
      if (!animationsPlaying && _playing) _heldMs = _nowMs();
      _workspaceAnimationsPlaying = animationsPlaying;
      if (animationsPlaying && _playing) {
        _clockOffsetMs = DateTime.now().millisecondsSinceEpoch - _heldMs;
      }
    }
    if (mounted) setState(() {});
  }

  /// The no-WebGL sprite for a node, off the catalogs the store holds now.
  String? _spriteFor(McSceneNode node) =>
      mcCatalogSpriteFor(_store.catalogs, node);

  /// The camera moved, or the stage gave up on WebGL: the anchored label and
  /// particles are this view's, so they are re-projected here. The scene and
  /// the animating flag are this view's own writes, and the controller stays
  /// quiet on an unchanged scene, so neither reaches this.
  void _onStageChanged() {
    if (!mounted) return;
    if (_lastCamera == _stage.camera && _lastWebGl == _stage.webGlAvailable) {
      return;
    }
    _lastCamera = _stage.camera;
    _lastWebGl = _stage.webGlAvailable;
    setState(() {});
  }

  int _nowMs() => _playing && _workspaceAnimationsPlaying
      ? DateTime.now().millisecondsSinceEpoch - _clockOffsetMs
      : _heldMs;

  void _togglePlaying() {
    setState(() {
      if (_playing) {
        _heldMs = _nowMs();
        _playing = false;
      } else {
        _clockOffsetMs = DateTime.now().millisecondsSinceEpoch - _heldMs;
        _playing = true;
      }
    });
  }

  /// The stack on the stage at [nowMs].
  ///
  /// Unpinned, that is the model-family rotation, advancing one entry per
  /// completed drop — every stack shares [dropStageCycleMs], so the count of
  /// finished cycles is the whole state the rotation needs.
  ShowcaseDrop _dropAt(int nowMs, int cycleMs) {
    final int? pinned = _pinned;
    if (pinned != null) return showcaseDrops[pinned % showcaseDrops.length];
    return dropStageRotationDrop(nowMs ~/ cycleMs);
  }

  /// Steps the shuffle button: off the rotation onto the first sample, then
  /// through the whole themed table, then back to the rotation.
  ///
  /// It pins rather than showing one cycle and handing back, because a stack
  /// somebody asked to see that vanishes two seconds later cannot be looked at
  /// — and wrapping past the end of the table is what keeps the rotation
  /// reachable again from the same one control.
  void _nextSample() {
    setState(() {
      final int? pinned = _pinned;
      final int next = pinned == null ? 0 : pinned + 1;
      _pinned = next >= showcaseDrops.length ? null : next;
    });
  }

  /// One repaint per server update, so the stage polls exactly as often as the
  /// plugin does and CSS eases between poses the way the client does.
  ///
  /// The period is part of the document, so a changed `updateIntervalTicks`
  /// has to replace the timer rather than keep the old cadence.
  void _syncTicker(GlossRealDropSettingsDoc doc) {
    final int periodMs =
        (doc.presentation.limits.updateIntervalTicks.clamp(1, 20) * 50).toInt();
    if (!_playing || !_workspaceAnimationsPlaying) {
      _ticker?.cancel();
      _ticker = null;
      return;
    }
    if (_ticker != null && _ticker!.isActive && _tickerPeriodMs == periodMs) {
      return;
    }
    _ticker?.cancel();
    _tickerPeriodMs = periodMs;
    _ticker = Timer.periodic(Duration(milliseconds: periodMs), (Timer _) {
      if (mounted) setState(() {});
    });
  }

  DropStageTimeline _timelineFor(
    GlossRealDropSettingsDoc doc,
    ShowcaseDrop drop,
  ) {
    if (_timeline == null ||
        !identical(_timelineNames, _store.workspaceNames) ||
        _timelineRevision != _store.glossRevision ||
        !identical(_timelineDrop, drop)) {
      _timeline = DropStageTimeline(
        doc,
        drop,
        environment: const DropStageEnvironment(water: false),
        names: _store.workspaceNames,
      );
      _timelineRevision = _store.glossRevision;
      _timelineNames = _store.workspaceNames;
      _timelineDrop = drop;
    }
    return _timeline!;
  }

  // --- build ----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final GlossRealDropSettingsDoc? doc = _store.realDropSettingsDoc;
    if (doc == null) {
      _ticker?.cancel();
      _ticker = null;
      return glossGameEmpty(
        anchor: GlossGameAnchor.world,
        label: huiText('Real drops in game'),
      );
    }

    final int nowMs = _nowMs();
    final ShowcaseDrop drop = _dropAt(
      nowMs,
      dropStageCycleMsFor(doc, water: false),
    );
    final GlossConditionContext itemContext = GlossConditionContext(
      variables: <String, Object?>{
        'drop.material': drop.registryName,
        'drop.amount': drop.amount.toDouble(),
        'drop.maxStackSize': drop.maxStackSize.toDouble(),
        'drop.world': 'world',
        'drop.onGround': false,
        'drop.inWater': false,
        'drop.inLava': false,
        'drop.playerDropped': true,
        'drop.customNamed': false,
        'source.present': true,
        'source.type': 'player',
        'subject.world': 'world',
        'world.name': 'world',
        'event.type': 'spawn',
        'event.playerDrop': true,
      },
    );
    final GlossRealDropPresentation selectedPresentation =
        glossResolveRealDropPresentation(doc, itemContext);
    final GlossRealDropSettingsDoc selectedDoc = GlossRealDropSettingsDoc(
      revision: doc.revision,
      presentation: selectedPresentation,
      audience: doc.audience.copy(),
    );
    _syncTicker(selectedDoc);
    final DropStageTimeline timeline = _timelineFor(selectedDoc, drop);
    final DropStageFrame frame = timeline.frameAt(nowMs);
    _syncStage(drop, frame);
    final List<Widget> overlay = _overlay(selectedDoc, frame, nowMs);

    return GlossGameScreen(
      anchor: GlossGameAnchor.world,
      label: huiText('Real drops in game'),
      // No water action: the frame stands in for the client's own view, and
      // a player does not get to flood the world either.
      actions: <RowAction>[
        RowAction(
          _playing ? huiText('Pause') : huiText('Play'),
          _togglePlaying,
          icon: _playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          iconOnly: true,
          pressed: _playing,
        ),
        RowAction(
          huiText('Next sample stack'),
          _nextSample,
          icon: ArcaneIcon.shuffle(),
          iconOnly: true,
        ),
      ],
      world: _stage,
      worldOverlay: overlay,
      child: const dom.div(<Widget>[]),
    );
  }

  /// Hands the frame to the stage: the displays and the shadow as scene
  /// nodes, drawn from the client's own pack. A material the
  /// pack has no geometry for goes as its catalog sprite, decoded into the
  /// pack once; the stage is woken when that lands so a parked stack shows.
  void _syncStage(ShowcaseDrop drop, DropStageFrame frame) {
    final McAssetPackData? pack = McAssetLoader.loaded;
    final bool spriteFallback =
        pack?.manifest.spriteFallbacks.contains(drop.material) ?? false;
    final String? spriteUrl = spriteFallback
        ? _store.catalogs.textureFor(drop.material)
        : null;
    if (pack != null && spriteUrl != null && !pack.hasImage(spriteUrl)) {
      unawaited(
        pack.prefetch(spriteUrl).then<void>((void _) {
          if (mounted) _stage.notify();
        }, onError: (Object _) {}),
      );
    }
    _stage.scene = McScene(
      realDropSceneNodes(
        frame: frame,
        material: drop.material,
        block: drop.block,
        water: false,
        origin: _origin,
        spriteFallback: spriteFallback,
        spriteUrl: spriteUrl,
      ),
      // The client eases a display between packets over its interpolation
      // window; a coarse `updateIntervalTicks` still reads as coarse because
      // the ease spans the whole gap.
      interpolationMs: _easeMs(frame),
    );
    _stage.animating = _playing && _workspaceAnimationsPlaying;
  }

  /// What the stage draws in DOM rather than on the canvas: the particle
  /// layers that ride the stack, and the label above it. Both are placed by
  /// the shared camera, so they land where the rendered stack is.
  List<Widget> _overlay(
    GlossRealDropSettingsDoc doc,
    DropStageFrame frame,
    int nowMs,
  ) {
    final GlossRealDropLabels labels = doc.presentation.labels;
    final List<GlossParticleLayer> carried = <GlossParticleLayer>[
      for (final GlossParticleLayer layer in doc.presentation.particleLayers)
        if (<String>{
          'projection',
          'component',
          'model',
          'local',
        }.contains(layer.target.scope))
          layer,
    ];
    return <Widget>[
      if (carried.isNotEmpty) _carrierParticles(carried, frame, nowMs),
      if (labels.enabled && glossShowMatches(labels.show, nowMs: nowMs))
        _label(labels, frame, nowMs, doc.presentation.particleLayers),
    ];
  }

  /// The stack's own particle scopes, on an anchor at the carrier so they ride
  /// it. The bounds are the model's, in blocks, the way the plugin states them.
  /// The client's interpolation window for this frame, in milliseconds.
  static int _easeMs(DropStageFrame frame) =>
      frame.interpolationTicks.clamp(0, 59) * 50;

  Widget _carrierParticles(
    List<GlossParticleLayer> layers,
    DropStageFrame frame,
    int nowMs,
  ) => dom.div(
    classes: 'hui-mc-anchor',
    styles: dom.Styles(
      raw: <String, String>{
        'transition': 'transform ${_easeMs(frame)}ms linear',
        'transform': mcDomAnchorTransform(
          camera: _stage.camera,
          position: McVec3(
            _origin.x,
            _origin.y + frame.carrierY,
            _origin.z + frame.carrierZ,
          ),
          billboard: McBillboardMode.center,
        ),
      },
    ),
    <Widget>[
      GlossParticleOverlay(
        layers: layers,
        pixelsPerBlock: huiPreviewPxPerBlock,
        tick: nowMs ~/ 50,
        scopeBounds: <String, List<GlossParticleRect>>{
          for (final String scope in <String>[
            'projection',
            'component',
            'model',
          ])
            scope: <GlossParticleRect>[
              GlossParticleRect(
                x: 0,
                y: 0,
                z: 0,
                width: frame.modelScale,
                height: frame.modelScale,
                depth: frame.modelScale,
              ),
            ],
        },
      ),
    ],
  );

  /// The `TextDisplay` the plugin parents to the carrier: the formatted name,
  /// at `labels.yOffset` blocks, at `labels.style.scaleX/Y/Z`, with the
  /// configured background colour and alpha, shadow and see-through depth
  /// order. The anchor carries the billboard, so `CENTER` and `VERTICAL`
  /// really do turn with the camera the way they do in game.
  Widget _label(
    GlossRealDropLabels labels,
    DropStageFrame frame,
    int nowMs,
    List<GlossParticleLayer> particleLayers,
  ) {
    final GlossLineRender render = renderGlossLine(
      frame.label,
      richText: true,
      animations: _store.workspaceAnimations,
      emoji: _store.workspaceEmoji,
      nowMs: nowMs,
    );
    return dom.div(
      classes: labels.style.seeThrough
          ? 'hui-mc-anchor is-see-through'
          : 'hui-mc-anchor',
      styles: dom.Styles(
        raw: <String, String>{
          'transition': 'transform ${_easeMs(frame)}ms linear',
          'transform': mcDomAnchorTransform(
            camera: _stage.camera,
            position: McVec3(
              _origin.x,
              _origin.y + frame.carrierY + frame.labelY,
              _origin.z + frame.carrierZ,
            ),
            billboard: McBillboardMode.parse(labels.style.billboard),
          ),
          'white-space': 'nowrap',
          // `seeThrough` is depth-test off in game. The stack is on the canvas
          // under this layer either way, so this only orders the anchors.
          'z-index': labels.style.seeThrough ? '8' : '4',
        },
      ),
      <Widget>[
        // The plate scales separately so `labels.style` grows the text around
        // the anchor the way a TextDisplay does, instead of dragging it away
        // from the stack.
        dom.div(
          styles: dom.Styles(
            raw: <String, String>{
              'transform':
                  'scale3d(${labels.style.scaleX}, ${labels.style.scaleY}, ${labels.style.scaleZ})',
              'transform-origin': '50% 100%',
              'background': 'transparent',
              'padding': '0',
              'color': '#fff',
            },
          ),
          <Widget>[
            GlossDisplayText(
              style: labels.style,
              box: labels.box,
              child: dom.div(
                styles: const dom.Styles(
                  raw: <String, String>{'position': 'relative'},
                ),
                <Widget>[
                  GlossParticleOverlay(
                    layers: <GlossParticleLayer>[
                      for (final GlossParticleLayer layer in particleLayers)
                        if (<String>{
                          'label',
                          'text',
                          'line',
                          'span',
                        }.contains(layer.target.scope))
                          layer,
                    ],
                    pixelsPerBlock: huiPreviewPxPerBlock,
                    tick: nowMs ~/ 50,
                    renderedText: GlossParticleTextRendered(
                      text: render.renderedText,
                      spans: render.particleSpans,
                    ),
                  ),
                  GlossTextLine(render: render),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }
}
