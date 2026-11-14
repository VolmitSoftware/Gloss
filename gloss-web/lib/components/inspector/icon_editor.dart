/// The shared icon editor: one icon slot as a plain group, its type first and
/// then the fields of that type.
///
/// Switching type never destroys work: the outgoing icon is parked in the
/// session cache and handed back on the way home (see `inspector_session.dart`).
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../logic/hui_geometry.dart';
import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../services/image_library.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/reorder_list.dart';
import '../../ui/technical_input.dart';
import 'block_picker.dart';
import 'custom_item_picker.dart';
import 'entity_picker.dart';
import 'extras_editor.dart';
import 'display_style_editor.dart';
import 'hologram_box_editor.dart';
import '../../model/gloss_hologram_box.dart';
import 'image_picker_grid.dart';
import 'inspector_session.dart';
import 'item_picker.dart';
import 'player_head_picker.dart';
import 'text_icon_editor.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

/// Which icon field of a component data object is being edited.
enum IconSlot { icon, trueIcon, falseIcon }

extension IconSlotNames on IconSlot {
  String get jsonKey => switch (this) {
    IconSlot.icon => 'icon',
    IconSlot.trueIcon => 'trueIcon',
    IconSlot.falseIcon => 'falseIcon',
  };

  String get label => switch (this) {
    IconSlot.icon => huiText('Icon'),
    IconSlot.trueIcon => huiText('True icon'),
    IconSlot.falseIcon => huiText('False icon'),
  };
}

/// Reads the icon a slot currently holds.
HuiIcon? readIconSlot(HuiComponentData data, IconSlot slot) => switch (data) {
  HuiRuntimeComponentData() => null,

  final HuiButtonData button => button.icon,
  final HuiDecorationData decoration => decoration.icon,
  final HuiToggleData toggle =>
    slot == IconSlot.falseIcon ? toggle.falseIcon : toggle.trueIcon,
};

/// Writes an icon back into a slot. Slot/type pairs that do not exist are
/// ignored rather than thrown: the inspector only renders slots the type has.
void writeIconSlot(HuiComponentData data, IconSlot slot, HuiIcon? icon) {
  if (data is HuiButtonData) {
    data.icon = icon;
    return;
  }
  if (data is HuiDecorationData) {
    data.icon = icon;
    return;
  }
  if (data is HuiToggleData) {
    if (slot == IconSlot.falseIcon) {
      data.falseIcon = icon;
    } else {
      data.trueIcon = icon;
    }
  }
}

/// The name of an icon type in the type select and the Add menu.
String huiIconTypeLabel(String type) => switch (type) {
  'text' => huiText('Text'),
  'textImage' => huiText('Image'),
  'animatedTextImage' => huiText('Animated'),
  'item' => huiText('Item'),
  'block' => huiText('Block'),
  'customItem' => huiText('Custom item'),
  'entity' => huiText('Entity'),
  'playerHead' => huiText('Player head'),
  _ => type,
};

/// The tip beside the type select: how Gloss draws that type.
String huiIconTypeTip(String type) => switch (type) {
  'textImage' => huiText(
    'Gloss draws one character per source pixel. Imports are resized '
    'to at most 16x16 because the text renderer is for compact pixel '
    'art and larger glyph walls cause poor client performance.',
  ),
  'item' => huiText(
    'Items render at roughly 0.75 blocks square and are the only '
    'icon type with a fixed size: uiScale is the only thing that '
    'changes it.',
  ),
  'block' => huiText(
    'The runtime uses the material\'s default block state in a packet-only '
    'block display. Directional state properties are not authored here.',
  ),
  'customItem' => huiText(
    'The server asks the provider plugin for the item when the '
    'menu opens and renders whatever stack it hands back, textures '
    'and model data included.',
  ),
  'entity' => huiText(
    'The component anchor is the entity\'s feet. Gloss keeps body, pitch and '
    'head yaw aligned with the menu and disables player pushing. Width and '
    'height define the editor silhouette and a button or toggle\'s click '
    'plane, not the client model; decorations have no click plane.',
  ),
  'playerHead' => huiText(
    'The canvas draws the generic head for every name: a skin comes from '
    'a Mojang profile the server looks up and caches, and this editor has '
    'no server. Geometry is exact, because a head is drawn on the same '
    'item display an item icon uses.',
  ),
  _ => huiText(huiIconTypeDescriptions[type] ?? ''),
};

class IconEditor extends StatelessWidget {
  const IconEditor({
    required this.store,
    required this.images,
    required this.catalogs,
    required this.session,
    required this.componentId,
    required this.slot,
    required this.icon,
    this.catalogsLoading = false,
    this.issues = const <HuiIssue>[],
    super.key,
  });

  final EditorStore store;
  final ImageLibrary images;
  final HuiCatalogs catalogs;
  final InspectorSession session;
  final String componentId;
  final IconSlot slot;
  final HuiIcon? icon;

  /// True until `assets/catalog/*.json` resolves; the catalog pickers say
  /// "Loading" instead of "No matches" until then.
  final bool catalogsLoading;

  /// Issues for this component, already filtered by the caller.
  final List<HuiIssue> issues;

  String get _sessionKey =>
      InspectorSession.iconSlot(componentId, slot.jsonKey);

  String get _fieldId => 'hui-icon-$componentId-${slot.jsonKey}';

  List<HuiIssue> get _slotIssues {
    final String marker = '.${slot.jsonKey}';
    return issues
        .where((HuiIssue issue) => issue.path.contains(marker))
        .toList();
  }

  List<HuiIssue> _issuesEndingWith(String suffix) => _slotIssues
      .where((HuiIssue issue) => issue.path.endsWith(suffix))
      .toList();

  List<HuiIssue> _issuesContaining(String part) =>
      _slotIssues.where((HuiIssue issue) => issue.path.contains(part)).toList();

  void _write(String label, HuiIcon? next) {
    store.editComponent(componentId, label, (HuiComponent component) {
      writeIconSlot(component.data, slot, next);
    });
  }

  /// Extras are written onto the icon the store just snapshotted, not the one
  /// this widget was handed: `editComponent` runs inside `mutate`, so the
  /// object here is the live document's.
  void _writeExtras(String label, Map<String, dynamic> next) {
    store.editComponent(componentId, label, (HuiComponent component) {
      readIconSlot(component.data, slot)?.extras = next;
    });
  }

  void _writeStyle(String label, HuiIconStyle? next) {
    store.editComponent(componentId, label, (HuiComponent component) {
      readIconSlot(component.data, slot)?.style = next?.copy();
    });
  }

  void _switchType(String nextType) {
    final HuiIcon? current = icon;
    if (current != null && current.type == nextType) return;
    _write(
      '${slot.label.toLowerCase()} type $nextType',
      session.switchIcon(_sessionKey, current, nextType),
    );
  }

  void _add(String type) => _write(
    'add ${slot.label.toLowerCase()}',
    session.recallIcon(_sessionKey, type) ?? createDefaultIcon(type),
  );

  /// Acts at once: the session keeps the icon for this type and Undo puts
  /// it back.
  void _remove() {
    final HuiIcon? current = icon;
    if (current == null) return;
    session.rememberIcon(_sessionKey, current);
    _write('clear ${slot.label.toLowerCase()}', null);
    toastInfo(
      huiText('Deleted {name}.', <String, Object?>{'name': slot.label}),
      action: ToastAction(
        label: huiText('Undo'),
        onPressed: () => store.performUndo(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => FieldGroup(
    title: slot.label,
    tip: icon == null
        ? huiText(
            'Gloss draws its magenta checker here, and a button keeps '
            'its hitbox. Pick a type to start one.',
          )
        : null,
    actions: <Widget>[
      if (icon != null)
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Remove icon'),
          tipAtEnd: true,
          onPressed: _remove,
        ),
    ],
    children: icon == null ? <Widget>[_empty()] : _body(context),
  );

  Widget _empty() => EmptyState(
    message: huiText('No icon'),
    icon: ArcaneIcon.imageOff(),
    action: AppMenu(
      label: huiText('Add'),
      icon: ArcaneIcon.plus(),
      outline: true,
      entries: <AppMenuEntry>[
        for (final String type in huiIconTypes)
          AppMenuEntry(
            huiIconTypeLabel(type),
            () => _add(type),
            description: huiText(huiIconTypeDescriptions[type] ?? ''),
          ),
      ],
    ),
  );

  List<Widget> _body(BuildContext context) {
    final HuiIcon current = icon!;
    return <Widget>[
      Field(
        label: huiText('Type'),
        forId: '$_fieldId-type',
        tip: huiIconTypeTip(current.type),
        child: ArcaneSelect(
          id: '$_fieldId-type',
          value: current.type,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          options: <ArcaneSelectOption>[
            for (final String type in huiIconTypes)
              ArcaneSelectOption(label: huiIconTypeLabel(type), value: type),
          ],
          onChanged: _switchType,
        ),
      ),
      ..._typeFields(current),
      if (current case final HuiTextIcon text)
        HologramBoxEditor(
          box: text.box ?? GlossHologramBox(),
          issues: _issuesContaining('.box'),
          mutate: (String label, void Function(GlossHologramBox) edit) {
            final HuiTextIcon next = text.copy();
            next.box ??= GlossHologramBox();
            edit(next.box!);
            _write(label, next);
          },
        ),
      if (current is! HuiEntityIcon)
        DisplayStyleEditor(
          style: current.style,
          issues: _issuesContaining('.style'),
          onChanged: _writeStyle,
        ),
      if (current is HuiItemIcon ||
          current is HuiCustomItemIcon ||
          current is HuiPlayerHeadIcon ||
          current is HuiBlockIcon)
        ExtrasEditor(
          title: huiText('Presentation'),
          extensionKeys: false,
          extras: <String, Object?>{
            'name': current.extras['name'] ?? '',
            'lore': current.extras['lore'] ?? <String>[],
            if (current is HuiItemIcon || current is HuiCustomItemIcon)
              'countFormat': current.extras['countFormat'] ?? '&f&l{count}',
          },
          onChanged: (String label, Map<String, dynamic> next) {
            final HuiIcon edited = current.copy();
            for (final String key in <String>['name', 'lore', 'countFormat']) {
              if (next.containsKey(key)) {
                edited.extras[key] = huiDeepCopy(next[key]);
              }
            }
            _write(label, edited);
          },
        ),
      AdvancedOnly(
        child: ExtrasEditor(
          title: huiText('Icon'),
          extras: current.extras,
          knownKeys: const <String>{'name', 'lore', 'countFormat'},
          onChanged: _writeExtras,
        ),
      ),
    ];
  }

  List<Widget> _typeFields(HuiIcon current) => switch (current) {
    final HuiTextIcon text => <Widget>[
      TextIconEditor(
        // Keyed by field id so flipping a toggle from the true slot to
        // the false slot remounts the textarea. Without it the state is
        // reused, the user-dirty DOM field keeps the previous slot's text
        // and the next keystroke writes it into the other slot.
        key: ValueKey<String>(_fieldId),
        fieldId: _fieldId,
        text: text.text,
        emoji: store.workspaceEmoji,
        issues: _issuesEndingWith('.text'),
        label: huiText('Text'),
        onChanged: (String label, String value) => _write(
          label,
          HuiTextIcon(value, text.style?.copy(), text.refreshTicks)
            ..box = text.box?.copy()
            ..extras = huiDeepCopyMap(text.extras),
        ),
      ),
      _textRefresh(text),
    ],
    final HuiTextImageIcon image => <Widget>[
      _ImageIconEditor(
        icon: image,
        images: images,
        idPrefix: _fieldId,
        issues: _issuesEndingWith('.path'),
        onChanged: _write,
      ),
    ],
    final HuiAnimatedImageIcon animated => <Widget>[
      _AnimatedIconEditor(
        icon: animated,
        images: images,
        idPrefix: _fieldId,
        issues: _slotIssues,
        onChanged: _write,
        onUndo: () => store.performUndo(),
      ),
    ],
    final HuiItemIcon item => <Widget>[
      ItemIconEditor(
        icon: item,
        catalogs: catalogs,
        issues: _slotIssues,
        loading: catalogsLoading,
        idPrefix: _fieldId,
        onChanged: (String label, HuiItemIcon next) => _write(label, next),
      ),
    ],
    final HuiBlockIcon block => <Widget>[
      BlockIconEditor(
        icon: block,
        catalogs: catalogs,
        issues: _slotIssues,
        loading: catalogsLoading,
        idPrefix: _fieldId,
        onChanged: (String label, HuiBlockIcon next) => _write(label, next),
      ),
    ],
    final HuiCustomItemIcon custom => <Widget>[
      CustomItemIconEditor(
        icon: custom,
        catalogs: catalogs,
        issues: _slotIssues,
        loading: catalogsLoading,
        idPrefix: _fieldId,
        onChanged: (String label, HuiCustomItemIcon next) =>
            _write(label, next),
      ),
    ],
    final HuiEntityIcon entity => <Widget>[
      EntityIconEditor(
        icon: entity,
        issues: _slotIssues,
        idPrefix: _fieldId,
        onChanged: (String label, HuiEntityIcon next) => _write(label, next),
      ),
    ],
    final HuiPlayerHeadIcon head => <Widget>[
      PlayerHeadIconEditor(
        icon: head,
        issues: _slotIssues,
        idPrefix: _fieldId,
        onChanged: (String label, HuiPlayerHeadIcon next) =>
            _write(label, next),
      ),
    ],
  };

  Widget _textRefresh(HuiTextIcon text) {
    final int fallback = glossTextRequiresFastRefresh(text.text)
        ? 1
        : huiRuntimeDefaultTextRefreshTicks;
    HuiTextIcon withTicks(int? ticks) =>
        HuiTextIcon(text.text, text.style?.copy(), ticks)
          ..box = text.box?.copy()
          ..extras = huiDeepCopyMap(text.extras);
    return Field(
      label: huiText('Dynamic text refresh'),
      forId: '$_fieldId-refresh',
      docKey: 'icon.text.refreshTicks',
      problems: _issuesEndingWith('.refreshTicks'),
      defaultValue: huiPlural(
        'duration.tick_count',
        fallback,
        oneEnglish: '{count} tick',
        otherEnglish: '{count} ticks',
      ),
      onReset: text.refreshTicks == null
          ? null
          : () => _write('dynamic text refresh', withTicks(null)),
      child: DurationField(
        id: '$_fieldId-refresh',
        value: (text.refreshTicks ?? fallback).toDouble(),
        unit: HuiDurationUnit.ticks,
        min: 0,
        max: 1200,
        onChanged: (double value) =>
            _write('dynamic text refresh', withTicks(value.round())),
      ),
    );
  }
}

class _ImageIconEditor extends StatelessWidget {
  const _ImageIconEditor({
    required this.icon,
    required this.images,
    required this.idPrefix,
    required this.onChanged,
    required this.issues,
  });

  final HuiTextImageIcon icon;
  final ImageLibrary images;
  final String idPrefix;
  final void Function(String label, HuiIcon icon) onChanged;
  final List<HuiIssue> issues;

  void _setPath(String path) => onChanged(
    'image path',
    HuiTextImageIcon(path, icon.style?.copy())
      ..extras = huiDeepCopyMap(icon.extras),
  );

  /// One source pixel is one character cell wide and one text line tall.
  static String _blocks(int pixels) =>
      (pixels * huiCharCell).toStringAsFixed(2);

  @override
  Widget build(BuildContext context) {
    final StoredImage? stored = images.byPath(icon.path);
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Path'),
        required: true,
        forId: '$idPrefix-path',
        docKey: 'icon.textImage.path',
        problems: issues,
        child: TextInput(
          id: '$idPrefix-path',
          value: icon.path,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          placeholder: huiText('logo.png'),
          onChanged: _setPath,
          attributes: technicalInputAttributes,
          styles: technicalInputStyles,
        ),
      ),
      if (stored != null) ...<Widget>[
        Field(
          label: huiText('Preview'),
          child: dom.div(<Widget>[
            huiPixelImage(stored, box: 96, alt: stored.path),
          ]),
        ),
        Field(
          label: huiText('Source size'),
          child: dom.span(<Widget>[
            Text(
              huiText(
                '{width}x{height} px ({characterWidth}, {lineHeight})',
                <String, Object?>{
                  'width': stored.width,
                  'height': stored.height,
                  'characterWidth': huiPlural(
                    'image.width_characters.count',
                    stored.width,
                    oneEnglish: '{count} character wide',
                    otherEnglish: '{count} characters wide',
                  ),
                  'lineHeight': huiPlural(
                    'image.height_lines.count',
                    stored.height,
                    oneEnglish: '{count} line tall',
                    otherEnglish: '{count} lines tall',
                  ),
                },
              ),
            ),
          ]),
        ),
        Field(
          label: huiText('In-game size'),
          child: dom.span(<Widget>[
            Text(
              huiText(
                '{width} x {height} blocks at uiScale 1',
                <String, Object?>{
                  'width': _blocks(stored.width),
                  'height': _blocks(stored.height),
                },
              ),
            ),
          ]),
        ),
      ],
      Field(
        label: huiText('Image library'),
        child: Component.fragment(<Widget>[
          ImagePickerGrid(
            images: images,
            selected: icon.path,
            onPicked: _setPath,
          ),
          ImageUploadButton(
            images: images,
            inputId: '$idPrefix-upload',
            label: huiText('Upload PNG, WebP or GIF'),
            onAdded: (List<String> paths) {
              if (paths.isNotEmpty) _setPath(paths.first);
            },
          ),
        ]),
      ),
    ]);
  }
}

class _AnimatedIconEditor extends StatefulWidget {
  const _AnimatedIconEditor({
    required this.icon,
    required this.images,
    required this.idPrefix,
    required this.onChanged,
    required this.onUndo,
    required this.issues,
  });

  final HuiAnimatedImageIcon icon;
  final ImageLibrary images;
  final String idPrefix;
  final void Function(String label, HuiIcon icon) onChanged;

  /// Takes back the last document edit (the toast after a removed frame).
  final void Function() onUndo;
  final List<HuiIssue> issues;

  @override
  State<_AnimatedIconEditor> createState() => _AnimatedIconEditorState();
}

class _AnimatedIconEditorState extends State<_AnimatedIconEditor> {
  /// Library paths picked but not yet added, in the order they were picked,
  /// which is the order they land in as frames. Staging is what makes the
  /// grid a multi-select: one click per frame, then one edit, then one undo
  /// step.
  final List<String> _staged = <String>[];

  HuiAnimatedImageIcon get _icon => component.icon;

  List<String> get _source => _icon.source;

  HuiAnimatedImageIcon _with(List<String> source, int speed) =>
      HuiAnimatedImageIcon(source, speed, _icon.style?.copy())
        ..extras = huiDeepCopyMap(_icon.extras);

  void _emit(String label, List<String> source, [int? speed]) =>
      component.onChanged(label, _with(source, speed ?? _icon.speed));

  void _toggleStaged(String path) => setState(() {
    if (!_staged.remove(path)) _staged.add(path);
  });

  void _addStaged() {
    if (_staged.isEmpty) return;
    final List<String> added = <String>[..._staged];
    setState(_staged.clear);
    _emit('frames.add:${added.length}', <String>[..._source, ...added]);
  }

  void _addFrames(List<String> paths) {
    if (paths.isEmpty) return;
    _emit('frames.add:${paths.length}', <String>[..._source, ...paths]);
  }

  /// Acts at once; Undo brings the frame back.
  void _removeFrame(int index) {
    final String name = _frameName(index);
    _emit('remove frame', <String>[..._source]..removeAt(index));
    toastInfo(
      huiText('Deleted {name}.', <String, Object?>{'name': name}),
      action: ToastAction(
        label: huiText('Undo'),
        onPressed: () => component.onUndo(),
      ),
    );
  }

  void _duplicateFrame(int index) => _emit(
    'duplicate frame',
    <String>[..._source]..insert(index + 1, _source[index]),
  );

  void _reverse() =>
      _emit('reverse frames', _source.reversed.toList(growable: false));

  /// `removeAt(from)` then `insert(to, moved)`, which is what [ReorderList]
  /// promises and what keeps a drop to a single document edit.
  void _reorder(int from, int to) {
    if (from == to || to < 0 || to >= _source.length) return;
    final List<String> next = <String>[..._source];
    final String moved = next.removeAt(from);
    next.insert(to, moved);
    _emit('reorder frames', next);
  }

  void _setFrame(int index, String path) {
    final List<String> next = <String>[..._source];
    next[index] = path;
    _emit('frame path', next);
  }

  String _frameName(int index) => huiText(
    "frame {value} of {length}",
    <String, Object?>{'value': index + 1, 'length': _source.length},
  );

  List<HuiIssue> _issuesWhere(bool Function(String path) test) =>
      component.issues.where((HuiIssue issue) => test(issue.path)).toList();

  @override
  Widget build(BuildContext context) {
    final int speed = _icon.speed;
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Frames'),
        docKey: 'icon.animated.source',
        problems: _issuesWhere((String path) => path.contains('.source')),
        trailing: AppIconButton(
          icon: ArcaneIcon.arrowUpDown(),
          label: huiText('Reverse frame order'),
          tipAtEnd: true,
          onPressed: _source.length < 2 ? null : _reverse,
        ),
        child: _source.isEmpty
            ? EmptyState(
                message: huiText('No frames'),
                icon: ArcaneIcon.imageOff(),
              )
            : ReorderList(
                label: huiText('Frames'),
                itemCount: _source.length,
                tools: false,
                onReorder: _reorder,
                itemBuilder: _frameRow,
              ),
      ),
      if (_source.isNotEmpty)
        Field(
          label: huiText('Preview'),
          tip: huiText(
            'Every frame is padded to the tallest frame with blank rows.',
          ),
          child: AnimatedIconPreview(
            images: component.images,
            frames: _source,
            speed: speed,
          ),
        ),
      Field(
        label: huiText('Add from library'),
        tip: huiText(
          'Click images to line them up, then add them in one go. The same '
          'image can be used in more than one frame.',
        ),
        child: Component.fragment(<Widget>[
          ImagePickerGrid(
            images: component.images,
            staged: _staged,
            onPicked: _toggleStaged,
            label: huiText('Add from library'),
          ),
          if (_staged.isNotEmpty)
            ActionRow(
              dense: true,
              cancel: RowAction(
                huiText('Clear'),
                () => setState(_staged.clear),
              ),
              primary: RowAction(
                huiPlural(
                  'icon.frames_add.count',
                  _staged.length,
                  oneEnglish: 'Add {count} frame',
                  otherEnglish: 'Add {count} frames',
                ),
                _addStaged,
                icon: ArcaneIcon.plus(),
              ),
            ),
          ImageUploadButton(
            images: component.images,
            inputId: '${component.idPrefix}-upload',
            label: huiText('Upload image or GIF frames'),
            onAdded: _addFrames,
          ),
        ]),
      ),
      Field(
        label: huiText('Speed'),
        required: true,
        forId: '${component.idPrefix}-speed',
        docKey: 'icon.animated.speed',
        problems: _issuesWhere((String path) => path.endsWith('.speed')),
        defaultValue: huiPlural(
          'duration.tick_count',
          huiDefaultAnimationSpeed,
          oneEnglish: '{count} tick',
          otherEnglish: '{count} ticks',
        ),
        onReset: speed == huiDefaultAnimationSpeed
            ? null
            : () => _emit('animation speed', <String>[
                ..._source,
              ], huiDefaultAnimationSpeed),
        child: DurationField(
          id: '${component.idPrefix}-speed',
          value: speed.toDouble(),
          unit: HuiDurationUnit.ticks,
          min: 2,
          perLabel: huiText('per frame'),
          onChanged: (double value) =>
              _emit('animation speed', <String>[..._source], value.round()),
        ),
      ),
    ]);
  }

  Widget _frameRow(int index) {
    final String path = _source[index];
    final StoredImage? stored = component.images.byPath(path);
    final String name = _frameName(index);
    final List<AppMenuEntry> entries = <AppMenuEntry>[
      AppMenuEntry(
        huiText('Duplicate'),
        () => _duplicateFrame(index),
        icon: ArcaneIcon.copy(),
      ),
      AppMenuEntry(
        huiText('Move up'),
        index == 0 ? null : () => _reorder(index, index - 1),
        icon: ArcaneIcon.arrowUp(),
      ),
      AppMenuEntry(
        huiText('Move down'),
        index >= _source.length - 1 ? null : () => _reorder(index, index + 1),
        icon: ArcaneIcon.arrowDown(),
      ),
      const AppMenuEntry.separator(),
      AppMenuEntry(
        huiText('Remove'),
        () => _removeFrame(index),
        icon: ArcaneIcon.trash2(),
        destructive: true,
      ),
    ];
    return dom.div(
      attributes: const <String, String>{'data-frame-row': ''},
      <Widget>[
        if (stored != null)
          huiPixelImage(stored, box: 24)
        else
          ArcaneIcon.imageOff(),
        dom.div(
          attributes: const <String, String>{'data-frame-path': ''},
          <Widget>[
            TextInput(
              value: path,
              size: controlSize(DensityScope.of(context)),
              fullWidth: true,
              onChanged: (String value) => _setFrame(index, value),
              attributes: <String, String>{
                ...technicalInputAttributes,
                'aria-label': name,
              },
              styles: technicalInputStyles,
            ),
          ],
        ),
        AppMenu(
          id: '${component.idPrefix}-frame-$index',
          icon: ArcaneIcon.ellipsis(),
          iconLabel: huiText('Actions for {label}', <String, Object?>{
            'label': name,
          }),
          alignEnd: true,
          entries: entries,
        ),
      ],
    );
  }
}

/// Inline playback of an animated icon at the real tick rate.
///
/// One frame every `speed` ticks, 50 ms per tick, exactly like
/// `AnimatedImageMenuIcon.tick`.
class AnimatedIconPreview extends StatefulWidget {
  const AnimatedIconPreview({
    required this.images,
    required this.frames,
    required this.speed,
    super.key,
  });

  final ImageLibrary images;
  final List<String> frames;
  final int speed;

  @override
  State<AnimatedIconPreview> createState() => _AnimatedIconPreviewState();
}

class _AnimatedIconPreviewState extends State<AnimatedIconPreview> {
  Timer? _timer;
  int _frame = 0;
  bool _playing = true;

  @override
  void initState() {
    super.initState();
    _restart();
  }

  @override
  void didUpdateWidget(AnimatedIconPreview oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.speed != component.speed ||
        oldComponent.frames.length != component.frames.length) {
      _restart();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _timer = null;
    super.dispose();
  }

  void _restart() {
    _timer?.cancel();
    _timer = null;
    if (!_playing || component.frames.length < 2) return;
    final int ticks = component.speed < 2 ? 2 : component.speed;
    _timer = Timer.periodic(Duration(milliseconds: ticks * 50), (Timer _) {
      if (!mounted) return;
      setState(() => _frame = (_frame + 1) % component.frames.length);
    });
  }

  void _togglePlay() {
    setState(() => _playing = !_playing);
    _restart();
  }

  @override
  Widget build(BuildContext context) {
    final int index = component.frames.isEmpty
        ? 0
        : _frame % component.frames.length;
    final StoredImage? stored = component.frames.isEmpty
        ? null
        : component.images.byPath(component.frames[index]);
    return ActionRow(
      dense: true,
      leading: <Widget>[
        if (stored != null)
          huiPixelImage(stored, box: 48, alt: stored.path)
        else
          ArcaneIcon.imageOff(),
        dom.span(<Widget>[
          Text(
            huiText("frame {value} of {length}", <String, Object?>{
              'value': index + 1,
              'length': component.frames.length,
            }),
          ),
        ]),
      ],
      secondary: <RowAction>[
        RowAction(
          _playing ? huiText('Pause preview') : huiText('Play preview'),
          _togglePlay,
          icon: _playing ? ArcaneIcon.pause() : ArcaneIcon.play(),
          iconOnly: true,
          pressed: _playing,
        ),
      ],
    );
  }
}
