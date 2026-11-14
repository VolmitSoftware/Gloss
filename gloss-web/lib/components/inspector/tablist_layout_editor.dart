/// The tab list's fixed layout: the switch, the presentation being edited
/// (the base layout or one variant, picked at the top) and that
/// presentation's fixed cells, roster sections and skins, then the variants'
/// own keys. Every part is one section of items with their fields, never
/// deeper. Inputs keep a full accessible name ("Base layout cell 1 text")
/// under a short visible label.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../model/gloss_tab_layout.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import 'gloss_visibility_editor.dart';

class TablistLayoutEditor extends StatefulWidget {
  const TablistLayoutEditor({
    required this.value,
    required this.onChanged,
    super.key,
  });
  final GlossTabLayout? value;
  final void Function(GlossTabLayout) onChanged;

  @override
  State<TablistLayoutEditor> createState() => _TablistLayoutEditorState();
}

class _TablistLayoutEditorState extends State<TablistLayoutEditor> {
  /// The presentation the cell, roster and skin sections edit: -1 for the
  /// base layout, else a variant index.
  int _editing = -1;

  void _edit(void Function(GlossTabLayout) edit) {
    final GlossTabLayout copy = component.value?.copy() ?? GlossTabLayout();
    edit(copy);
    component.onChanged(copy);
  }

  @override
  Widget build(BuildContext context) {
    final GlossTabLayout? layout = component.value;
    final int editing = layout == null || _editing >= layout.variants.length
        ? -1
        : _editing;
    return Component.fragment(<Widget>[
      AppSection(
        title: huiText('Layout'),
        memoryKey: 'tablist.layout',
        children: <Widget>[
          SwitchField(
            id: 'tab-layout-enabled',
            label: huiText('Enable fixed layout'),
            value: layout?.enabled ?? false,
            onChanged: (bool enabled) =>
                _edit((GlossTabLayout copy) => copy.enabled = enabled),
          ),
          if (layout != null) ...<Widget>[
            GlossVisibilityEditor(
              raw: layout.show,
              sectionKey: 'tablist.layout.visibility',
              onChanged: (Object? show) =>
                  _edit((GlossTabLayout copy) => copy.show = show),
            ),
            if (layout.variants.isNotEmpty)
              Field(
                label: huiText('Presentation'),
                forId: 'tab-layout-editing',
                child: ArcaneSelect(
                  id: 'tab-layout-editing',
                  value: '$editing',
                  size: controlSize(DensityScope.of(context)),
                  fullWidth: true,
                  options: <ArcaneSelectOption>[
                    ArcaneSelectOption(
                      value: '-1',
                      label: huiText('Base layout'),
                    ),
                    for (int index = 0; index < layout.variants.length; index++)
                      ArcaneSelectOption(
                        value: '$index',
                        label: _variantName(index),
                      ),
                  ],
                  onChanged: (String picked) =>
                      setState(() => _editing = int.tryParse(picked) ?? -1),
                ),
              ),
          ],
        ],
      ),
      if (layout != null) ...<Widget>[
        TablistPresentationEditor(
          key: ValueKey<int>(editing),
          value: editing < 0 ? layout : layout.variants[editing].presentation,
          prefix: editing < 0 ? huiText('Base layout') : _variantName(editing),
          onChanged: (GlossTabPresentation edited) => editing < 0
              ? _edit((GlossTabLayout copy) {
                  copy.entries = edited.entries;
                  copy.slots = edited.slots;
                  copy.sections = edited.sections;
                  copy.skins = edited.skins;
                })
              : _edit(
                  (GlossTabLayout copy) =>
                      copy.variants[editing].presentation = edited,
                ),
        ),
        _variants(context, layout),
      ],
    ]);
  }

  String _variantName(int index) =>
      huiText('Layout variant {index}', <String, Object?>{'index': index + 1});

  Widget _variants(BuildContext context, GlossTabLayout layout) => AppSection(
    title: huiText('Variants'),
    memoryKey: 'tablist.layout.variants',
    count: layout.variants.length,
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add layout variant'),
        onPressed: () => _edit((GlossTabLayout copy) {
          String id = 'variant';
          int suffix = 1;
          while (copy.variants.any(
            (GlossTabLayoutVariant variant) => variant.id == id,
          )) {
            id = 'variant_${suffix++}';
          }
          copy.variants.add(
            GlossTabLayoutVariant(
              id: id,
              presentation: GlossTabPresentation(
                entries: copy.entries,
                slots: <GlossTabSlot>[
                  for (final GlossTabSlot slot in copy.slots) slot.copy(),
                ],
                sections: <GlossTabSection>[
                  for (final GlossTabSection section in copy.sections)
                    section.copy(),
                ],
                skins: <String, GlossTabSkin>{
                  for (final MapEntry<String, GlossTabSkin> skin
                      in copy.skins.entries)
                    skin.key: skin.value.copy(),
                },
              ),
            ),
          );
        }),
      ),
    ],
    children: <Widget>[
      for (int index = 0; index < layout.variants.length; index++)
        AppSection(
          title: _variantName(index),
          memoryKey: 'tab.layout.variant.$index',
          actions: <Widget>[
            AppIconButton(
              icon: ArcaneIcon.trash2(),
              label: huiText('Remove layout variant {index}', <String, Object?>{
                'index': index + 1,
              }),
              onPressed: () =>
                  _edit((GlossTabLayout copy) => copy.variants.removeAt(index)),
            ),
          ],
          children: <Widget>[
            AdvancedOnly(
              child: _text(
                context,
                id: 'tab-layout-variant-$index-id',
                label: huiText('Id'),
                name: huiText('Layout variant {index} id', <String, Object?>{
                  'index': index + 1,
                }),
                value: layout.variants[index].id,
                changed: (String text) => _edit(
                  (GlossTabLayout copy) => copy.variants[index].id = text,
                ),
              ),
            ),
            AdvancedOnly(
              child: _integer(
                id: 'tab-layout-variant-$index-priority',
                label: huiText('Priority'),
                name: huiText(
                  'Layout variant {index} priority',
                  <String, Object?>{'index': index + 1},
                ),
                value: layout.variants[index].priority,
                changed: (int number) => _edit(
                  (GlossTabLayout copy) =>
                      copy.variants[index].priority = number,
                ),
              ),
            ),
            _text(
              context,
              id: 'tab-layout-variant-$index-when',
              label: huiText('When'),
              name: huiText(
                'Layout variant {index} condition',
                <String, Object?>{'index': index + 1},
              ),
              value: layout.variants[index].when,
              changed: (String text) => _edit(
                (GlossTabLayout copy) => copy.variants[index].when = text,
              ),
            ),
          ],
        ),
    ],
  );
}

/// One presentation's entry count, fixed cells, roster sections and skins,
/// as three sections of items. [prefix] names the presentation in every
/// input's accessible name.
class TablistPresentationEditor extends StatelessWidget {
  const TablistPresentationEditor({
    required this.value,
    required this.prefix,
    required this.onChanged,
    super.key,
  });
  final GlossTabPresentation value;
  final String prefix;
  final void Function(GlossTabPresentation) onChanged;

  void _edit(void Function(GlossTabPresentation) edit) {
    final GlossTabPresentation copy = value.copy();
    edit(copy);
    onChanged(copy);
  }

  void _slot(int index, void Function(GlossTabSlot) edit) =>
      _edit((GlossTabPresentation copy) => edit(copy.slots[index]));
  void _section(int index, void Function(GlossTabSection) edit) =>
      _edit((GlossTabPresentation copy) => edit(copy.sections[index]));

  /// The id stem of this presentation's inputs.
  String get _stem => 'tab-layout-${prefix.hashCode.toUnsigned(32)}';

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    AppSection(
      title: huiText('Slots'),
      memoryKey: 'tablist.layout.slots',
      count: value.slots.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add {prefix} fixed cell', <String, Object?>{
            'prefix': prefix,
          }),
          onPressed: () => _edit(
            (GlossTabPresentation copy) => copy.slots.add(GlossTabSlot()),
          ),
        ),
      ],
      children: <Widget>[
        _integer(
          id: '$_stem-entries',
          label: huiText('Entries'),
          name: huiText('{prefix} entries', <String, Object?>{
            'prefix': prefix,
          }),
          value: value.entries,
          hint: huiText(
            '{columns} columns × {rows} rows; Java 1.21.2 or later.',
            <String, Object?>{'columns': value.columns, 'rows': value.rows},
          ),
          changed: (int number) =>
              _edit((GlossTabPresentation copy) => copy.entries = number),
        ),
        for (int index = 0; index < value.slots.length; index++)
          _cell(context, index),
      ],
    ),
    AppSection(
      title: huiText('Sections'),
      memoryKey: 'tablist.layout.rosters',
      count: value.sections.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add {prefix} roster section', <String, Object?>{
            'prefix': prefix,
          }),
          onPressed: () => _edit((GlossTabPresentation copy) {
            String id = 'players';
            int suffix = 1;
            while (copy.sections.any(
              (GlossTabSection section) => section.id == id,
            )) {
              id = 'players_${suffix++}';
            }
            copy.sections.add(
              GlossTabSection(id: id, rows: copy.rows.clamp(1, 20)),
            );
          }),
        ),
      ],
      children: <Widget>[
        for (int index = 0; index < value.sections.length; index++)
          _roster(context, index),
      ],
    ),
    AppSection(
      title: huiText('Skins'),
      memoryKey: 'tablist.layout.skins',
      count: value.skins.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add {prefix} skin', <String, Object?>{
            'prefix': prefix,
          }),
          onPressed: () => _edit((GlossTabPresentation copy) {
            String name = 'skin';
            int suffix = 1;
            while (copy.skins.containsKey(name)) {
              name = 'skin_${suffix++}';
            }
            copy.skins[name] = GlossTabSkin();
          }),
        ),
      ],
      children: <Widget>[
        for (final String name in value.skins.keys) _skin(context, name),
      ],
    ),
  ]);

  Widget _cell(BuildContext context, int index) {
    final GlossTabSlot slot = value.slots[index];
    final String id = '$_stem-cell-$index';
    return FieldOwner(
      name: huiText('Fixed cell {index}', <String, Object?>{
        'index': index + 1,
      }),
      child: AppSection(
        title: huiText('Fixed cell {index}', <String, Object?>{
          'index': index + 1,
        }),
        memoryKey: 'tablist.layout.cell.$index',
        actions: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove {prefix} cell {index}', <String, Object?>{
              'prefix': prefix,
              'index': index + 1,
            }),
            onPressed: () => _edit(
              (GlossTabPresentation copy) => copy.slots.removeAt(index),
            ),
          ),
        ],
        children: <Widget>[
          _integer(
            id: '$id-column',
            label: huiText('Column'),
            name: huiText('{prefix} cell {index} column', <String, Object?>{
              'prefix': prefix,
              'index': index + 1,
            }),
            value: slot.column,
            changed: (int n) => _slot(index, (GlossTabSlot cell) {
              cell.column = n;
            }),
          ),
          _integer(
            id: '$id-row',
            label: huiText('Row'),
            name: huiText('{prefix} cell {index} row', <String, Object?>{
              'prefix': prefix,
              'index': index + 1,
            }),
            value: slot.row,
            changed: (int n) =>
                _slot(index, (GlossTabSlot cell) => cell.row = n),
          ),
          _text(
            context,
            id: '$id-text',
            label: huiText('Text'),
            name: huiText('{prefix} cell {index} text', <String, Object?>{
              'prefix': prefix,
              'index': index + 1,
            }),
            value: slot.text,
            technical: false,
            changed: (String text) =>
                _slot(index, (GlossTabSlot cell) => cell.text = text),
          ),
          _text(
            context,
            id: '$id-skin',
            label: huiText('Skin'),
            name: huiText('{prefix} cell {index} skin', <String, Object?>{
              'prefix': prefix,
              'index': index + 1,
            }),
            value: slot.skin ?? '',
            changed: (String text) => _slot(
              index,
              (GlossTabSlot cell) => cell.skin = text.isEmpty ? null : text,
            ),
          ),
          _text(
            context,
            id: '$id-ping',
            label: huiText('Ping'),
            name: huiText('{prefix} cell {index} ping', <String, Object?>{
              'prefix': prefix,
              'index': index + 1,
            }),
            value: slot.ping?.toString() ?? '',
            changed: (String text) {
              if (text.isEmpty || int.tryParse(text) != null) {
                _slot(
                  index,
                  (GlossTabSlot cell) => cell.ping = int.tryParse(text),
                );
              }
            },
          ),
          SwitchField(
            id: '$id-hat',
            label: huiText('Hat'),
            value: slot.hat,
            onChanged: (bool b) =>
                _slot(index, (GlossTabSlot cell) => cell.hat = b),
          ),
        ],
      ),
    );
  }

  Widget _roster(BuildContext context, int index) {
    final GlossTabSection section = value.sections[index];
    final String id = '$_stem-roster-$index';
    final String label = huiText('{prefix} roster {index}', <String, Object?>{
      'prefix': prefix,
      'index': index + 1,
    });
    return AppSection(
      title: huiText('Roster {index}', <String, Object?>{'index': index + 1}),
      memoryKey: 'tablist.layout.roster.$index',
      actions: <Widget>[
        if (section.sort.length < 16)
          AppIconButton(
            icon: ArcaneIcon.plus(),
            label: huiText('Add {label} sort key', <String, Object?>{
              'label': label,
            }),
            onPressed: () => _section(
              index,
              (GlossTabSection row) =>
                  row.sort = <GlossTabSortKey>[...row.sort, GlossTabSortKey()],
            ),
          ),
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Remove {label}', <String, Object?>{'label': label}),
          onPressed: () => _edit(
            (GlossTabPresentation copy) => copy.sections.removeAt(index),
          ),
        ),
      ],
      children: <Widget>[
        _text(
          context,
          id: '$id-id',
          label: huiText('Id'),
          name: huiText('{label} id', <String, Object?>{'label': label}),
          value: section.id,
          changed: (String text) =>
              _section(index, (GlossTabSection row) => row.id = text),
        ),
        _integer(
          id: '$id-column',
          label: huiText('Column'),
          name: huiText('{label} column', <String, Object?>{'label': label}),
          value: section.column,
          changed: (int n) =>
              _section(index, (GlossTabSection row) => row.column = n),
        ),
        _integer(
          id: '$id-row',
          label: huiText('Row'),
          name: huiText('{label} row', <String, Object?>{'label': label}),
          value: section.row,
          changed: (int n) =>
              _section(index, (GlossTabSection row) => row.row = n),
        ),
        _integer(
          id: '$id-columns',
          label: huiText('Columns'),
          name: huiText('{label} columns', <String, Object?>{'label': label}),
          value: section.columns,
          changed: (int n) =>
              _section(index, (GlossTabSection row) => row.columns = n),
        ),
        _integer(
          id: '$id-rows',
          label: huiText('Rows'),
          name: huiText('{label} rows', <String, Object?>{'label': label}),
          value: section.rows,
          changed: (int n) =>
              _section(index, (GlossTabSection row) => row.rows = n),
        ),
        _text(
          context,
          id: '$id-filter',
          label: huiText('Filter'),
          name: huiText('{label} filter', <String, Object?>{'label': label}),
          value: section.filter,
          changed: (String text) =>
              _section(index, (GlossTabSection row) => row.filter = text),
        ),
        _text(
          context,
          id: '$id-format',
          label: huiText('Format'),
          name: huiText('{label} name format', <String, Object?>{
            'label': label,
          }),
          value: section.format ?? '',
          placeholder: r'$player',
          changed: (String text) => _section(
            index,
            (GlossTabSection row) => row.format = text.isEmpty ? null : text,
          ),
        ),
        Field(
          label: huiText('Overflow'),
          child: ToggleChoice<String>(
            label: huiText('{label} overflow', <String, Object?>{
              'label': label,
            }),
            value: section.overflow,
            options: const <ChoiceOption<String>>[
              ChoiceOption<String>('hide', 'hide'),
              ChoiceOption<String>('count', 'count'),
            ],
            onChanged: (String text) =>
                _section(index, (GlossTabSection row) => row.overflow = text),
          ),
        ),
        _text(
          context,
          id: '$id-overflow-format',
          label: huiText('Format'),
          name: huiText('{label} overflow format', <String, Object?>{
            'label': label,
          }),
          value: section.overflowFormat,
          technical: false,
          changed: (String text) => _section(
            index,
            (GlossTabSection row) => row.overflowFormat = text,
          ),
        ),
        _text(
          context,
          id: '$id-skin',
          label: huiText('Skin'),
          name: huiText('{label} skin', <String, Object?>{'label': label}),
          value: section.skin ?? '',
          changed: (String text) => _section(
            index,
            (GlossTabSection row) => row.skin = text.isEmpty ? null : text,
          ),
        ),
        SwitchField(
          id: '$id-hat',
          label: huiText('Hat'),
          value: section.hat,
          onChanged: (bool b) =>
              _section(index, (GlossTabSection row) => row.hat = b),
        ),
        SwitchField(
          id: '$id-npcs',
          label: huiText('Include NPCs'),
          value: section.includeNpcs,
          onChanged: (bool b) =>
              _section(index, (GlossTabSection row) => row.includeNpcs = b),
        ),
        if (section.sort.isNotEmpty)
          Field(
            label: huiText('Sort'),
            child: ReorderList(
              label: huiText('Sort'),
              itemCount: section.sort.length,
              onReorder: (int from, int to) =>
                  _section(index, (GlossTabSection row) {
                    final GlossTabSortKey moved = row.sort.removeAt(from);
                    row.sort.insert(to, moved);
                  }),
              onRemove: (int key) => _section(
                index,
                (GlossTabSection row) => row.sort.removeAt(key),
              ),
              itemBuilder: (int key) => _sortKey(context, index, key, label),
            ),
          ),
      ],
    );
  }

  Widget _sortKey(BuildContext context, int index, int key, String label) {
    final GlossTabSortKey sort = value.sections[index].sort[key];
    // The expression over its two icon choices.
    return ArcaneFieldContent(
      children: <Widget>[
        TextInput(
          id: '$_stem-roster-$index-sort-$key',
          value: sort.expression,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          onChanged: (String text) => _section(
            index,
            (GlossTabSection row) => row.sort[key].expression = text,
          ),
          styles: technicalInputStyles,
          attributes: <String, String>{
            ...technicalInputAttributes,
            'aria-label': huiText(
              '{label} sort {key} expression',
              <String, Object?>{'label': label, 'key': key + 1},
            ),
          },
        ),
        ActionRow(
          dense: true,
          leading: <Widget>[
            ToggleChoice<String>(
              label: huiText('{label} sort {key} type', <String, Object?>{
                'label': label,
                'key': key + 1,
              }),
              value: sort.type,
              options: <ChoiceOption<String>>[
                ChoiceOption<String>(
                  'text',
                  'text',
                  icon: ArcaneIcon.caseSensitive(),
                  iconOnly: true,
                ),
                ChoiceOption<String>(
                  'number',
                  'number',
                  icon: ArcaneIcon.hash(),
                  iconOnly: true,
                ),
              ],
              onChanged: (String text) => _section(
                index,
                (GlossTabSection row) => row.sort[key].type = text,
              ),
            ),
            ToggleChoice<String>(
              label: huiText('{label} sort {key} direction', <String, Object?>{
                'label': label,
                'key': key + 1,
              }),
              value: sort.direction,
              options: <ChoiceOption<String>>[
                ChoiceOption<String>(
                  'ascending',
                  'ascending',
                  icon: ArcaneIcon.arrowUpNarrowWide(),
                  iconOnly: true,
                ),
                ChoiceOption<String>(
                  'descending',
                  'descending',
                  icon: ArcaneIcon.arrowDownWideNarrow(),
                  iconOnly: true,
                ),
              ],
              onChanged: (String text) => _section(
                index,
                (GlossTabSection row) => row.sort[key].direction = text,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _skin(BuildContext context, String name) {
    final String id = '$_stem-skin-${name.hashCode.toUnsigned(32)}';
    return AppSection(
      title: huiText('Skin {name}', <String, Object?>{'name': name}),
      memoryKey: 'tablist.layout.skin.$name',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Remove {prefix} skin {name}', <String, Object?>{
            'prefix': prefix,
            'name': name,
          }),
          onPressed: () =>
              _edit((GlossTabPresentation copy) => copy.skins.remove(name)),
        ),
      ],
      children: <Widget>[
        _text(
          context,
          id: '$id-name',
          label: huiText('Name'),
          name: huiText('{prefix} skin {name} name', <String, Object?>{
            'prefix': prefix,
            'name': name,
          }),
          value: name,
          changed: (String next) {
            if (next.isEmpty ||
                (next != name && value.skins.containsKey(next))) {
              return;
            }
            _edit((GlossTabPresentation copy) {
              final GlossTabSkin skin = copy.skins.remove(name)!;
              copy.skins[next] = skin;
            });
          },
        ),
        _text(
          context,
          id: '$id-value',
          label: huiText('Value'),
          name: huiText('{prefix} skin {name} value', <String, Object?>{
            'prefix': prefix,
            'name': name,
          }),
          value: value.skins[name]!.value,
          changed: (String text) => _edit(
            (GlossTabPresentation copy) => copy.skins[name]!.value = text,
          ),
        ),
        _text(
          context,
          id: '$id-signature',
          label: huiText('Signature'),
          name: huiText('{prefix} skin {name} signature', <String, Object?>{
            'prefix': prefix,
            'name': name,
          }),
          value: value.skins[name]!.signature ?? '',
          changed: (String text) => _edit(
            (GlossTabPresentation copy) =>
                copy.skins[name]!.signature = text.isEmpty ? null : text,
          ),
        ),
      ],
    );
  }
}

/// A text field: the short visible [label], the full accessible [name] on
/// the input. Ids, keys and expressions are technical (left to right, no
/// spellcheck); game text is not.
Widget _text(
  BuildContext context, {
  required String id,
  required String label,
  required String name,
  required String value,
  required void Function(String) changed,
  String? placeholder,
  bool technical = true,
}) => Field(
  label: label,
  forId: id,
  child: TextInput(
    id: id,
    value: value,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    placeholder: placeholder,
    onChanged: changed,
    styles: technical ? technicalInputStyles : null,
    attributes: <String, String>{
      if (technical) ...technicalInputAttributes else 'spellcheck': 'false',
      'aria-label': name,
    },
  ),
);

/// A whole-number field, named like [_text].
Widget _integer({
  required String id,
  required String label,
  required String name,
  required int value,
  required void Function(int) changed,
  String? hint,
}) => Field(
  label: label,
  forId: id,
  hint: hint,
  child: NumberField(
    id: id,
    label: name,
    value: value.toDouble(),
    integer: true,
    step: 1,
    onChanged: (double number) => changed(number.round()),
  ),
);
