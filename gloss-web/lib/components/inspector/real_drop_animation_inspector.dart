library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/technical_input.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

String _animationTriggerLabel(GlossRealDropAnimationTrigger trigger) =>
    switch (trigger) {
      GlossRealDropAnimationTrigger.spawn => huiText('Spawn'),
      GlossRealDropAnimationTrigger.airborne => huiText('Airborne'),
      GlossRealDropAnimationTrigger.rebounding => huiText('Rebounding'),
      GlossRealDropAnimationTrigger.rolling => huiText('Rolling'),
      GlossRealDropAnimationTrigger.sliding => huiText('Sliding'),
      GlossRealDropAnimationTrigger.settling => huiText('Settling'),
      GlossRealDropAnimationTrigger.settled => huiText('Settled'),
      GlossRealDropAnimationTrigger.submerged => huiText('Submerged'),
      GlossRealDropAnimationTrigger.floating => huiText('Floating'),
      GlossRealDropAnimationTrigger.impact => huiText('Impact'),
      GlossRealDropAnimationTrigger.bounce => huiText('Bounce'),
      GlossRealDropAnimationTrigger.enterFluid => huiText('Enter fluid'),
      GlossRealDropAnimationTrigger.exitFluid => huiText('Exit fluid'),
      GlossRealDropAnimationTrigger.startRoll => huiText('Start roll'),
      GlossRealDropAnimationTrigger.settle => huiText('Settle'),
      GlossRealDropAnimationTrigger.wake => huiText('Wake'),
    };

String _animationTargetLabel(GlossRealDropAnimationTarget target) =>
    switch (target) {
      GlossRealDropAnimationTarget.offsetX => huiText('Offset X'),
      GlossRealDropAnimationTarget.offsetY => huiText('Offset Y'),
      GlossRealDropAnimationTarget.offsetZ => huiText('Offset Z'),
      GlossRealDropAnimationTarget.rotationX => huiText('Rotation X'),
      GlossRealDropAnimationTarget.rotationY => huiText('Rotation Y'),
      GlossRealDropAnimationTarget.rotationZ => huiText('Rotation Z'),
      GlossRealDropAnimationTarget.scaleX => huiText('Scale X'),
      GlossRealDropAnimationTarget.scaleY => huiText('Scale Y'),
      GlossRealDropAnimationTarget.scaleZ => huiText('Scale Z'),
      GlossRealDropAnimationTarget.glow => huiText('Glow'),
      GlossRealDropAnimationTarget.visible => huiText('Visible'),
      GlossRealDropAnimationTarget.physics => huiText('Physics'),
      GlossRealDropAnimationTarget.lightLevel => huiText('Light level'),
    };

String _animationBlendLabel(GlossRealDropAnimationBlend blend) =>
    switch (blend) {
      GlossRealDropAnimationBlend.add => huiTextKey(
        'animation_blend.add',
        'Add',
      ),
      GlossRealDropAnimationBlend.replace => huiTextKey(
        'animation_blend.replace',
        'Replace',
      ),
      GlossRealDropAnimationBlend.multiply => huiTextKey(
        'animation_blend.multiply',
        'Multiply',
      ),
    };

String _animationEasingLabel(GlossRealDropAnimationEasing easing) =>
    switch (easing) {
      GlossRealDropAnimationEasing.linear => huiText('Linear'),
      GlossRealDropAnimationEasing.hold => huiText('Hold'),
      GlossRealDropAnimationEasing.easeIn => huiText('Ease in'),
      GlossRealDropAnimationEasing.easeOut => huiText('Ease out'),
      GlossRealDropAnimationEasing.easeInOut => huiText('Ease in/out'),
      GlossRealDropAnimationEasing.backOut => huiText('Back out'),
    };

/// Animation profiles of the selected presentation. Profiles nest clips,
/// clips nest tracks and tracks nest keyframes, so one profile, one clip and
/// one track are picked at a time and their fields edited here; the pane
/// never stacks one disclosure inside another.
class RealDropAnimationInspector extends StatefulWidget {
  const RealDropAnimationInspector({
    required this.store,
    this.variantIndex,
    super.key,
  });

  final EditorStore store;
  final int? variantIndex;

  @override
  State<RealDropAnimationInspector> createState() =>
      _RealDropAnimationInspectorState();
}

class _RealDropAnimationInspectorState
    extends State<RealDropAnimationInspector> {
  static const String _idPrefix = 'real-drops-animation';

  int _profile = 0;
  int _clip = 0;
  int _track = 0;

  /// The inspector pane is dense; its text inputs match the kit fields.
  ComponentSize get _size => controlSize(Density.dense);

  /// [selected] kept inside a list of [length]; -1 for an empty list.
  static int _clamp(int selected, int length) =>
      length == 0 ? -1 : selected.clamp(0, length - 1);

  @override
  Widget build(BuildContext context) {
    final GlossRealDropSettingsDoc? doc = component.store.realDropSettingsDoc;
    final GlossRealDropAnimation? animation = doc == null
        ? null
        : _presentation(doc).animation;
    return AppSection(
      title: huiText('Animation authoring'),
      memoryKey: 'realDrops.animation',
      docKey: 'realDrops.animation',
      children: <Widget>[
        SwitchField(
          label: huiText('Run animation profiles'),
          docKey: 'realDrops.animation.enabled',
          value: animation?.enabled ?? false,
          onChanged: (bool value) => _mutate(
            'drop animation',
            (GlossRealDropAnimation edited) => edited.enabled = value,
          ),
        ),
        ..._materialProperties(animation),
        ..._profiles(animation),
      ],
    );
  }

  List<Widget> _materialProperties(GlossRealDropAnimation? animation) {
    final List<MapEntry<String, Map<String, GlossRealDropMaterialProperties>>>
    maps =
        animation?.materialProperties.entries.toList() ??
        const <
          MapEntry<String, Map<String, GlossRealDropMaterialProperties>>
        >[];
    return <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          _heading(
            huiText('Material properties'),
            docKey: 'realDrops.animation.materialProperties',
          ),
        ],
        secondary: <RowAction>[
          RowAction(
            huiText('Add property map'),
            () => _mutate('add material property map', (
              GlossRealDropAnimation edited,
            ) {
              final String name = _freshName(
                'properties',
                edited.materialProperties.keys,
              );
              edited.materialProperties[name] =
                  <String, GlossRealDropMaterialProperties>{
                    '*': GlossRealDropMaterialProperties(),
                  };
            }),
            icon: ArcaneIcon.plus(),
          ),
        ],
      ),
      for (int mapIndex = 0; mapIndex < maps.length; mapIndex++)
        ..._propertyMap(maps[mapIndex], mapIndex),
    ];
  }

  List<Widget> _propertyMap(
    MapEntry<String, Map<String, GlossRealDropMaterialProperties>> map,
    int mapIndex,
  ) {
    final List<MapEntry<String, GlossRealDropMaterialProperties>> entries = map
        .value
        .entries
        .toList();
    final String id = '$_idPrefix-map-$mapIndex';
    return <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          dom.h4(classes: 'ui-section-title', <Widget>[
            Text(
              huiText('{key} · {materials}', <String, Object?>{
                'key': map.key,
                'materials': huiPlural(
                  'real_drop.material_count',
                  entries.length,
                  oneEnglish: '{count} material',
                  otherEnglish: '{count} materials',
                ),
              }),
            ),
          ]),
        ],
        secondary: <RowAction>[
          RowAction(
            huiText('Add material'),
            () => _mutate('add material property', (
              GlossRealDropAnimation edited,
            ) {
              final Map<String, GlossRealDropMaterialProperties> target =
                  edited.materialProperties[map.key]!;
              target[_freshName('MATERIAL', target.keys)] =
                  GlossRealDropMaterialProperties();
            }),
            icon: ArcaneIcon.plus(),
            iconOnly: true,
          ),
          RowAction(
            huiText('Remove map'),
            () => _mutate(
              'remove material property map',
              (GlossRealDropAnimation edited) =>
                  edited.materialProperties.remove(map.key),
            ),
            icon: ArcaneIcon.trash2(),
            iconOnly: true,
            destructive: true,
          ),
        ],
      ),
      _text(
        huiText('Map name'),
        '$id-name',
        map.key,
        (String value) => _mutate('rename material property map', (
          GlossRealDropAnimation edited,
        ) {
          final Map<String, GlossRealDropMaterialProperties>? moved = edited
              .materialProperties
              .remove(map.key);
          if (moved != null) edited.materialProperties[value.trim()] = moved;
        }),
      ),
      for (int entryIndex = 0; entryIndex < entries.length; entryIndex++)
        _propertyEntry(map.key, entries[entryIndex], '$id-entry-$entryIndex'),
    ];
  }

  Widget _propertyEntry(
    String mapName,
    MapEntry<String, GlossRealDropMaterialProperties> entry,
    String id,
  ) => dom.div(classes: 'hui-drop-animation-row is-material', <Widget>[
    _text(
      huiText('Material glob'),
      '$id-glob',
      entry.key,
      (String value) => _mutate('edit material property glob', (
        GlossRealDropAnimation edited,
      ) {
        final Map<String, GlossRealDropMaterialProperties> target =
            edited.materialProperties[mapName]!;
        final GlossRealDropMaterialProperties? moved = target.remove(entry.key);
        if (moved != null) target[value.trim()] = moved;
      }),
    ),
    _number(
      huiText('Glow ARGB'),
      '$id-glow',
      entry.value.glow,
      (double value) => _mutate(
        'edit material glow',
        (GlossRealDropAnimation edited) =>
            edited.materialProperties[mapName]![entry.key]!.glow = value,
      ),
    ),
    _number(
      huiText('Light 0..15'),
      '$id-light',
      entry.value.lightLevel,
      (double value) => _mutate(
        'edit material light',
        (GlossRealDropAnimation edited) =>
            edited.materialProperties[mapName]![entry.key]!.lightLevel = value,
      ),
    ),
    AppIconButton(
      icon: ArcaneIcon.trash2(),
      label: huiText('Remove'),
      tipAtEnd: true,
      onPressed: () => _mutate(
        'remove material property',
        (GlossRealDropAnimation edited) =>
            edited.materialProperties[mapName]!.remove(entry.key),
      ),
    ),
  ]);

  List<Widget> _profiles(GlossRealDropAnimation? animation) {
    final List<GlossRealDropAnimationProfile> profiles =
        animation?.profiles ?? const <GlossRealDropAnimationProfile>[];
    final int profileIndex = _clamp(_profile, profiles.length);
    return <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          _heading(
            huiText('Profiles and clips'),
            docKey: 'realDrops.animation.profiles',
          ),
        ],
        secondary: <RowAction>[
          RowAction(huiText('Add hover/release sequence'), () {
            final int added = profiles.length;
            _mutate('add hover release sequence', (
              GlossRealDropAnimation edited,
            ) {
              final String id = _freshName(
                'hover-showcase',
                edited.profiles.map(
                  (GlossRealDropAnimationProfile profile) => profile.id,
                ),
              );
              edited.enabled = true;
              edited.profiles.add(_hoverReleaseProfile(id));
            });
            _pickProfile(added);
          }),
          RowAction(huiText('Add profile'), () {
            final int added = profiles.length;
            _mutate(
              'add animation profile',
              (GlossRealDropAnimation edited) => edited.profiles.add(
                GlossRealDropAnimationProfile(
                  id: _freshName(
                    'profile',
                    edited.profiles.map(
                      (GlossRealDropAnimationProfile profile) => profile.id,
                    ),
                  ),
                ),
              ),
            );
            _pickProfile(added);
          }, icon: ArcaneIcon.plus()),
        ],
      ),
      if (profileIndex >= 0) ...<Widget>[
        _picker(
          label: huiText('Profile'),
          id: '$_idPrefix-profile',
          selected: profileIndex,
          options: <String>[
            for (final GlossRealDropAnimationProfile profile in profiles)
              huiText('{id} · priority {priority}', <String, Object?>{
                'id': profile.id,
                'priority': profile.priority,
              }),
          ],
          onPicked: _pickProfile,
          tools: <Widget>[
            AppIconButton(
              icon: ArcaneIcon.trash2(),
              label: huiText('Remove profile'),
              tipAtEnd: true,
              onPressed: () => _mutate(
                'remove animation profile',
                (GlossRealDropAnimation edited) =>
                    edited.profiles.removeAt(profileIndex),
              ),
            ),
          ],
        ),
        ..._profileFields(profiles[profileIndex], profileIndex),
      ],
    ];
  }

  void _pickProfile(int index) => setState(() {
    _profile = index;
    _clip = 0;
    _track = 0;
  });

  List<Widget> _profileFields(
    GlossRealDropAnimationProfile profile,
    int profileIndex,
  ) {
    final int clipIndex = _clamp(_clip, profile.clips.length);
    return <Widget>[
      _text(
        huiText('Profile id'),
        '$_idPrefix-profile-id',
        profile.id,
        (String value) => _editProfile(
          profileIndex,
          'rename animation profile',
          (GlossRealDropAnimationProfile edited) => edited.id = value,
        ),
        docKey: 'realDrops.animation.profile.id',
      ),
      _number(
        huiText('Priority'),
        '$_idPrefix-profile-priority',
        profile.priority.toDouble(),
        (double value) => _editProfile(
          profileIndex,
          'edit animation profile priority',
          (GlossRealDropAnimationProfile edited) =>
              edited.priority = value.round(),
        ),
        docKey: 'realDrops.animation.profile.priority',
        integer: true,
      ),
      _text(
        huiText('Material globs'),
        '$_idPrefix-profile-materials',
        profile.materials.join(', '),
        (String value) => _editProfile(
          profileIndex,
          'edit animation profile materials',
          (GlossRealDropAnimationProfile edited) =>
              edited.materials = _split(value),
        ),
        docKey: 'realDrops.animation.profile.materials',
      ),
      _picker(
        label: huiText('Clip'),
        id: '$_idPrefix-clip',
        docKey: 'realDrops.animation.profile.clips',
        selected: clipIndex,
        options: <String>[
          for (final GlossRealDropAnimationClip clip in profile.clips)
            huiText('{trigger} · {durationTicks} ticks', <String, Object?>{
              'trigger': _animationTriggerLabel(clip.trigger),
              'durationTicks': clip.durationTicks,
            }),
        ],
        onPicked: _pickClip,
        tools: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.plus(),
            label: huiText('Add clip'),
            onPressed: () {
              final int added = profile.clips.length;
              _editProfile(
                profileIndex,
                'add animation clip',
                (GlossRealDropAnimationProfile edited) => edited.clips.add(
                  GlossRealDropAnimationClip(durationTicks: 20),
                ),
              );
              _pickClip(added);
            },
          ),
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove clip'),
            tipAtEnd: true,
            onPressed: clipIndex < 0
                ? null
                : () => _editProfile(
                    profileIndex,
                    'remove animation clip',
                    (GlossRealDropAnimationProfile edited) =>
                        edited.clips.removeAt(clipIndex),
                  ),
          ),
        ],
      ),
      if (clipIndex >= 0)
        ..._clipFields(profile.clips[clipIndex], profileIndex, clipIndex),
    ];
  }

  void _pickClip(int index) => setState(() {
    _clip = index;
    _track = 0;
  });

  List<Widget> _clipFields(
    GlossRealDropAnimationClip clip,
    int profileIndex,
    int clipIndex,
  ) {
    final int trackIndex = _clamp(_track, clip.tracks.length);
    return <Widget>[
      _select(
        huiText('Trigger'),
        '$_idPrefix-clip-trigger',
        clip.trigger.wire,
        <({String label, String value})>[
          for (final GlossRealDropAnimationTrigger value
              in GlossRealDropAnimationTrigger.values)
            (label: _animationTriggerLabel(value), value: value.wire),
        ],
        (String value) => _editClip(
          profileIndex,
          clipIndex,
          'edit animation trigger',
          (GlossRealDropAnimationClip edited) =>
              edited.trigger = GlossRealDropAnimationTrigger.parse(value),
        ),
        docKey: 'realDrops.animation.clip.trigger',
      ),
      _number(
        huiText('Duration ticks'),
        '$_idPrefix-clip-duration',
        clip.durationTicks,
        (double value) => _editClip(
          profileIndex,
          clipIndex,
          'edit animation duration',
          (GlossRealDropAnimationClip edited) => edited.durationTicks = value,
        ),
        docKey: 'realDrops.animation.clip.durationTicks',
      ),
      SwitchField(
        label: huiText('Loop clip'),
        docKey: 'realDrops.animation.clip.loop',
        value: clip.loop,
        onChanged: (bool value) => _editClip(
          profileIndex,
          clipIndex,
          'toggle animation loop',
          (GlossRealDropAnimationClip edited) => edited.loop = value,
        ),
      ),
      _picker(
        label: huiText('Track'),
        id: '$_idPrefix-track',
        docKey: 'realDrops.animation.clip.tracks',
        selected: trackIndex,
        options: <String>[
          for (final GlossRealDropAnimationTrack track in clip.tracks)
            huiText('{target} · {blend}', <String, Object?>{
              'target': _animationTargetLabel(track.target),
              'blend': _animationBlendLabel(track.blend),
            }),
        ],
        onPicked: (int index) => setState(() => _track = index),
        tools: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.plus(),
            label: huiText('Add track'),
            onPressed: () {
              final int added = clip.tracks.length;
              _editClip(
                profileIndex,
                clipIndex,
                'add animation track',
                (GlossRealDropAnimationClip edited) => edited.tracks.add(
                  GlossRealDropAnimationTrack(
                    keyframes: <GlossRealDropAnimationKeyframe>[
                      GlossRealDropAnimationKeyframe(),
                      GlossRealDropAnimationKeyframe(
                        tick: edited.durationTicks,
                      ),
                    ],
                  ),
                ),
              );
              setState(() => _track = added);
            },
          ),
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove track'),
            tipAtEnd: true,
            onPressed: trackIndex < 0
                ? null
                : () => _editClip(
                    profileIndex,
                    clipIndex,
                    'remove animation track',
                    (GlossRealDropAnimationClip edited) =>
                        edited.tracks.removeAt(trackIndex),
                  ),
          ),
        ],
      ),
      if (trackIndex >= 0)
        ..._trackFields(
          clip.tracks[trackIndex],
          profileIndex,
          clipIndex,
          trackIndex,
        ),
    ];
  }

  List<Widget> _trackFields(
    GlossRealDropAnimationTrack track,
    int profileIndex,
    int clipIndex,
    int trackIndex,
  ) => <Widget>[
    _select(
      huiText('Target'),
      '$_idPrefix-track-target',
      track.target.wire,
      <({String label, String value})>[
        for (final GlossRealDropAnimationTarget value
            in GlossRealDropAnimationTarget.values)
          (label: _animationTargetLabel(value), value: value.wire),
      ],
      (String value) => _editTrack(
        profileIndex,
        clipIndex,
        trackIndex,
        'edit animation target',
        (GlossRealDropAnimationTrack edited) =>
            edited.target = GlossRealDropAnimationTarget.parse(value),
      ),
      docKey: 'realDrops.animation.track.target',
    ),
    Field(
      label: huiText('Blend'),
      docKey: 'realDrops.animation.track.blend',
      child: ToggleChoice<GlossRealDropAnimationBlend>(
        label: huiText('Blend'),
        value: track.blend,
        options: <ChoiceOption<GlossRealDropAnimationBlend>>[
          for (final GlossRealDropAnimationBlend value
              in GlossRealDropAnimationBlend.values)
            ChoiceOption<GlossRealDropAnimationBlend>(
              value,
              _animationBlendLabel(value),
            ),
        ],
        onChanged: (GlossRealDropAnimationBlend value) => _editTrack(
          profileIndex,
          clipIndex,
          trackIndex,
          'edit animation blend',
          (GlossRealDropAnimationTrack edited) => edited.blend = value,
        ),
      ),
    ),
    ActionRow(
      dense: true,
      leading: <Widget>[
        _heading(
          huiText('Keyframes'),
          docKey: 'realDrops.animation.track.keyframes',
          level: 4,
        ),
      ],
      secondary: <RowAction>[
        RowAction(
          huiText('Add keyframe'),
          () => _editTrack(
            profileIndex,
            clipIndex,
            trackIndex,
            'add animation keyframe',
            (GlossRealDropAnimationTrack edited) =>
                edited.keyframes.add(GlossRealDropAnimationKeyframe()),
          ),
          icon: ArcaneIcon.plus(),
          iconOnly: true,
        ),
      ],
    ),
    for (int frameIndex = 0; frameIndex < track.keyframes.length; frameIndex++)
      _keyframe(
        track.keyframes[frameIndex],
        profileIndex,
        clipIndex,
        trackIndex,
        frameIndex,
      ),
  ];

  Widget _keyframe(
    GlossRealDropAnimationKeyframe frame,
    int profileIndex,
    int clipIndex,
    int trackIndex,
    int frameIndex,
  ) {
    final String id = '$_idPrefix-keyframe-$frameIndex';
    return dom.div(classes: 'hui-drop-animation-row is-keyframe', <Widget>[
      _number(
        huiText('Tick'),
        '$id-tick',
        frame.tick,
        (double value) => _editFrame(
          profileIndex,
          clipIndex,
          trackIndex,
          frameIndex,
          'edit animation keyframe tick',
          (GlossRealDropAnimationKeyframe edited) => edited.tick = value,
        ),
      ),
      _number(
        huiText('Value'),
        '$id-value',
        frame.value,
        (double value) => _editFrame(
          profileIndex,
          clipIndex,
          trackIndex,
          frameIndex,
          'edit animation keyframe value',
          (GlossRealDropAnimationKeyframe edited) => edited.value = value,
        ),
      ),
      _select(
        huiText('Easing'),
        '$id-easing',
        frame.easing.wire,
        <({String label, String value})>[
          for (final GlossRealDropAnimationEasing value
              in GlossRealDropAnimationEasing.values)
            (label: _animationEasingLabel(value), value: value.wire),
        ],
        (String value) => _editFrame(
          profileIndex,
          clipIndex,
          trackIndex,
          frameIndex,
          'edit animation keyframe easing',
          (GlossRealDropAnimationKeyframe edited) =>
              edited.easing = GlossRealDropAnimationEasing.parse(value),
        ),
      ),
      _text(
        huiText('Material map'),
        '$id-map',
        frame.materialMap,
        (String value) => _editFrame(
          profileIndex,
          clipIndex,
          trackIndex,
          frameIndex,
          'edit animation material map',
          (GlossRealDropAnimationKeyframe edited) =>
              edited.materialMap = value.trim(),
        ),
      ),
      AppIconButton(
        icon: ArcaneIcon.trash2(),
        label: huiText('Remove'),
        tipAtEnd: true,
        onPressed: () => _editTrack(
          profileIndex,
          clipIndex,
          trackIndex,
          'remove animation keyframe',
          (GlossRealDropAnimationTrack edited) =>
              edited.keyframes.removeAt(frameIndex),
        ),
      ),
    ]);
  }

  /// A heading inside the section, with its (i) tip.
  Widget _heading(String title, {required String docKey, int level = 3}) {
    final List<Widget> children = <Widget>[
      TipLabel(label: Text(title), docKey: docKey),
    ];
    return level == 3
        ? dom.h3(classes: 'ui-section-title', children)
        : dom.h4(classes: 'ui-section-title', children);
  }

  /// Picks one entry of a nested list by its summary; [tools] add and remove
  /// entries at the end of the label line. An empty list shows "None".
  Widget _picker({
    required String label,
    required String id,
    required int selected,
    required List<String> options,
    required void Function(int index) onPicked,
    required List<Widget> tools,
    String? docKey,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    trailing: Component.fragment(tools),
    child: ArcaneSelect(
      id: id,
      value: selected < 0 ? null : '$selected',
      placeholder: selected < 0 ? huiText('None') : null,
      disabled: options.isEmpty,
      size: _size,
      fullWidth: true,
      options: <ArcaneSelectOption>[
        for (int index = 0; index < options.length; index++)
          ArcaneSelectOption(label: options[index], value: '$index'),
      ],
      onChanged: (String value) => onPicked(int.tryParse(value) ?? 0),
    ),
  );

  Widget _text(
    String label,
    String id,
    String value,
    void Function(String value) changed, {
    String? docKey,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      styles: technicalInputStyles,
      onChanged: changed,
      attributes: technicalInputAttributes,
    ),
  );

  /// Rows hold several numbers side by side, so these have no step buttons.
  Widget _number(
    String label,
    String id,
    double value,
    void Function(double value) changed, {
    String? docKey,
    bool integer = false,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    child: NumberField(
      id: id,
      value: value,
      step: integer ? 1 : 0.05,
      decimals: integer ? 0 : 2,
      integer: integer,
      steppers: false,
      onChanged: changed,
    ),
  );

  Widget _select(
    String label,
    String id,
    String value,
    List<({String label, String value})> options,
    void Function(String value) changed, {
    String? docKey,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    child: ArcaneSelect(
      id: id,
      value: value,
      fullWidth: true,
      size: _size,
      onChanged: changed,
      options: <ArcaneSelectOption>[
        for (final ({String label, String value}) option in options)
          ArcaneSelectOption(label: option.label, value: option.value),
      ],
    ),
  );

  GlossRealDropPresentation _presentation(GlossRealDropSettingsDoc doc) {
    final int? index = component.variantIndex;
    return index != null && index >= 0 && index < doc.variants.length
        ? doc.variants[index].presentation
        : doc.presentation;
  }

  void _mutate(
    String label,
    void Function(GlossRealDropAnimation animation) change,
  ) => component.store.mutateRealDropSettings(
    label,
    (GlossRealDropSettingsDoc doc) =>
        change(_presentation(doc).animation ??= GlossRealDropAnimation()),
  );

  void _editProfile(
    int profile,
    String label,
    void Function(GlossRealDropAnimationProfile profile) change,
  ) => _mutate(
    label,
    (GlossRealDropAnimation animation) => change(animation.profiles[profile]),
  );

  void _editClip(
    int profile,
    int clip,
    String label,
    void Function(GlossRealDropAnimationClip clip) change,
  ) => _editProfile(
    profile,
    label,
    (GlossRealDropAnimationProfile edited) => change(edited.clips[clip]),
  );

  void _editTrack(
    int profile,
    int clip,
    int track,
    String label,
    void Function(GlossRealDropAnimationTrack track) change,
  ) => _editClip(
    profile,
    clip,
    label,
    (GlossRealDropAnimationClip edited) => change(edited.tracks[track]),
  );

  void _editFrame(
    int profile,
    int clip,
    int track,
    int frame,
    String label,
    void Function(GlossRealDropAnimationKeyframe frame) change,
  ) => _editTrack(
    profile,
    clip,
    track,
    label,
    (GlossRealDropAnimationTrack edited) => change(edited.keyframes[frame]),
  );

  static List<String> _split(String value) => value
      .split(',')
      .map((String entry) => entry.trim())
      .where((String entry) => entry.isNotEmpty)
      .toList();

  static String _freshName(String prefix, Iterable<String> existing) {
    final Set<String> names = existing.toSet();
    for (int index = 1; ; index++) {
      final String name = '$prefix$index';
      if (!names.contains(name)) return name;
    }
  }

  static GlossRealDropAnimationProfile _hoverReleaseProfile(
    String id,
  ) => GlossRealDropAnimationProfile(
    id: id,
    priority: 100,
    materials: <String>['*'],
    clips: <GlossRealDropAnimationClip>[
      GlossRealDropAnimationClip(
        trigger: GlossRealDropAnimationTrigger.spawn,
        durationTicks: 60,
        tracks: <GlossRealDropAnimationTrack>[
          _showcaseScaleTrack(GlossRealDropAnimationTarget.scaleX),
          _showcaseScaleTrack(GlossRealDropAnimationTarget.scaleY),
          _showcaseScaleTrack(GlossRealDropAnimationTarget.scaleZ),
          GlossRealDropAnimationTrack(
            target: GlossRealDropAnimationTarget.offsetY,
            blend: GlossRealDropAnimationBlend.add,
            keyframes: <GlossRealDropAnimationKeyframe>[
              GlossRealDropAnimationKeyframe(tick: 0, value: 0),
              GlossRealDropAnimationKeyframe(
                tick: 8,
                value: 2,
                easing: GlossRealDropAnimationEasing.easeOut,
              ),
              GlossRealDropAnimationKeyframe(
                tick: 36,
                value: 2,
                easing: GlossRealDropAnimationEasing.hold,
              ),
              GlossRealDropAnimationKeyframe(
                tick: 60,
                value: 0,
                easing: GlossRealDropAnimationEasing.easeIn,
              ),
            ],
          ),
          GlossRealDropAnimationTrack(
            target: GlossRealDropAnimationTarget.physics,
            blend: GlossRealDropAnimationBlend.replace,
            keyframes: <GlossRealDropAnimationKeyframe>[
              GlossRealDropAnimationKeyframe(
                tick: 0,
                value: 0,
                easing: GlossRealDropAnimationEasing.hold,
              ),
              GlossRealDropAnimationKeyframe(
                tick: 36,
                value: 0,
                easing: GlossRealDropAnimationEasing.hold,
              ),
              GlossRealDropAnimationKeyframe(tick: 37, value: 1),
            ],
          ),
          _showcaseRotationTrack(GlossRealDropAnimationTarget.rotationX, 1080),
          _showcaseRotationTrack(GlossRealDropAnimationTarget.rotationY, 1440),
        ],
      ),
    ],
  );

  static GlossRealDropAnimationTrack _showcaseScaleTrack(
    GlossRealDropAnimationTarget target,
  ) => GlossRealDropAnimationTrack(
    target: target,
    blend: GlossRealDropAnimationBlend.multiply,
    keyframes: <GlossRealDropAnimationKeyframe>[
      GlossRealDropAnimationKeyframe(tick: 0, value: 4),
      GlossRealDropAnimationKeyframe(
        tick: 6,
        value: 0.15,
        easing: GlossRealDropAnimationEasing.easeIn,
      ),
      GlossRealDropAnimationKeyframe(
        tick: 12,
        value: 4,
        easing: GlossRealDropAnimationEasing.backOut,
      ),
      GlossRealDropAnimationKeyframe(
        tick: 20,
        value: 1,
        easing: GlossRealDropAnimationEasing.easeOut,
      ),
    ],
  );

  static GlossRealDropAnimationTrack _showcaseRotationTrack(
    GlossRealDropAnimationTarget target,
    double degrees,
  ) => GlossRealDropAnimationTrack(
    target: target,
    blend: GlossRealDropAnimationBlend.add,
    keyframes: <GlossRealDropAnimationKeyframe>[
      GlossRealDropAnimationKeyframe(tick: 0, value: 0),
      GlossRealDropAnimationKeyframe(
        tick: 60,
        value: degrees,
        easing: GlossRealDropAnimationEasing.easeInOut,
      ),
    ],
  );
}
