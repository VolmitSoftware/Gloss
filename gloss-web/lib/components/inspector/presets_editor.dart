/// The preset catalog's centre form: one collection at a time, its default
/// values and its named presets.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/document_presets.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';
import 'catalog_form.dart';
import 'extras_editor.dart';

class PresetsEditor extends StatefulWidget {
  const PresetsEditor({required this.store, super.key});
  final EditorStore store;

  @override
  State<PresetsEditor> createState() => _PresetsEditorState();
}

class _PresetsEditorState extends State<PresetsEditor> {
  String _collection = 'boards';
  String? _selected;
  String _newName = '';
  String? _error;

  void _edit(String label, void Function(GlossPresetsDoc) edit) =>
      component.store.mutateGloss(label, (GlossDoc doc) {
        if (doc is GlossPresetsDoc) {
          edit(doc);
        }
      });

  Map<String, Object?> _object(Object? raw) =>
      raw is Map<String, Object?> ? raw : <String, Object?>{};

  void _add(GlossPresetsDoc doc) {
    final String name = _newName.trim();
    if (name.isEmpty ||
        name.contains('/') ||
        name.contains('\\') ||
        name.contains('..')) {
      setState(
        () => _error = huiText(
          'Enter a name without slashes or consecutive dots.',
        ),
      );
      return;
    }
    if (_object(doc.presets[_collection]).containsKey(name)) {
      setState(
        () => _error = huiText(
          'That preset name already exists in this collection.',
        ),
      );
      return;
    }
    _edit('add preset', (GlossPresetsDoc doc) {
      doc.presets[_collection] = <String, Object?>{
        ..._object(doc.presets[_collection]),
        name: <String, Object?>{'values': <String, Object?>{}},
      };
    });
    setState(() {
      _selected = name;
      _newName = '';
      _error = null;
    });
  }

  void _definition(
    String name,
    String label,
    void Function(Map<String, Object?>) edit,
  ) {
    _edit(label, (GlossPresetsDoc doc) {
      final Map<String, Object?> entries = Map<String, Object?>.of(
        _object(doc.presets[_collection]),
      );
      final Map<String, Object?> definition = Map<String, Object?>.of(
        _object(entries[name]),
      );
      edit(definition);
      entries[name] = definition;
      doc.presets[_collection] = entries;
    });
  }

  void _remove(String name) =>
      _edit('remove preset', (GlossPresetsDoc doc) {
        final Map<String, Object?> entries = Map<String, Object?>.of(
          _object(doc.presets[_collection]),
        )..remove(name);
        if (entries.isEmpty) {
          doc.presets.remove(_collection);
        } else {
          doc.presets[_collection] = entries;
        }
      });

  @override
  Widget build(BuildContext context) =>
      CatalogFormHost(store: component.store, builder: _content);

  Widget _content(BuildContext context) {
    final GlossDoc? doc = component.store.glossDoc;
    if (doc is! GlossPresetsDoc) return const Component.fragment(<Widget>[]);
    final ComponentSize size = controlSize(DensityScope.of(context));
    final Map<String, Object?> entries = _object(doc.presets[_collection]);
    final String? selected = entries.containsKey(_selected)
        ? _selected
        : entries.keys.firstOrNull;
    final List<String> collections = DocumentPresets.kinds.toList()..sort();
    final bool defaultsBroken =
        doc.defaults[_collection] != null && doc.defaults[_collection] is! Map;
    final bool presetsBroken =
        doc.presets[_collection] != null && doc.presets[_collection] is! Map;
    return CatalogForm(
      kind: huiText('Presets'),
      title: component.store.menuId,
      revision: doc.revision,
      kindClass: 'is-presets',
      tip: huiText(
        'Defaults apply to every document in a collection. Named presets add shared values; each document can override them. Objects merge recursively and arrays replace completely.',
      ),
      children: <Widget>[
        Field(
          label: huiText('Collection'),
          forId: 'presets-collection',
          child: ArcaneSelect(
            id: 'presets-collection',
            value: _collection,
            size: size,
            fullWidth: true,
            options: <ArcaneSelectOption>[
              for (final String value in collections)
                ArcaneSelectOption(value: value, label: value),
            ],
            onChanged: (String value) => setState(() {
              _collection = value;
              _selected = null;
              _error = null;
            }),
          ),
        ),
        if (defaultsBroken)
          _note(
            huiText(
              'This collection must be a JSON object. Repair it in Code view.',
            ),
          )
        else
          ExtrasEditor(
            key: ValueKey<String>('defaults:$_collection'),
            title: huiText('Default values'),
            extensionKeys: false,
            extras: _object(doc.defaults[_collection]),
            onChanged: (String label, Map<String, Object?> values) =>
                _edit(label, (GlossPresetsDoc doc) {
                  if (values.isEmpty) {
                    doc.defaults.remove(_collection);
                  } else {
                    doc.defaults[_collection] = values;
                  }
                }),
          ),
        AppSection(
          title: huiText('Named presets'),
          memoryKey: 'presets.named',
          count: entries.length,
          children: <Widget>[
            if (presetsBroken)
              _note(
                huiText(
                  'This collection must be a JSON object. Repair it in Code view.',
                ),
              )
            else ...<Widget>[
              if (entries.isEmpty)
                _note(
                  huiText(
                    'No named presets in this collection. Enter a name to create one.',
                  ),
                ),
              if (selected != null) ...<Widget>[
                Field(
                  label: huiText('Preset'),
                  forId: 'presets-selected',
                  child: ArcaneSelect(
                    id: 'presets-selected',
                    value: selected,
                    size: size,
                    fullWidth: true,
                    options: <ArcaneSelectOption>[
                      for (final String name in entries.keys)
                        ArcaneSelectOption(value: name, label: name),
                    ],
                    onChanged: (String value) =>
                        setState(() => _selected = value),
                  ),
                ),
                ..._preset(selected, entries[selected], size),
              ],
              Field(
                label: huiText('New preset name'),
                forId: 'presets-new-name',
                error: _error,
                child: TextInput(
                  id: 'presets-new-name',
                  value: _newName,
                  size: size,
                  fullWidth: true,
                  attributes: technicalInputAttributes,
                  onChanged: (String value) => _newName = value,
                ),
              ),
              ActionRow(
                dense: true,
                secondary: <RowAction>[
                  RowAction(
                    huiText('Add preset'),
                    () => _add(doc),
                    icon: ArcaneIcon.plus(),
                  ),
                ],
              ),
            ],
          ],
        ),
      ],
    );
  }

  Widget _note(String text) => dom.p(
    classes: 'ui-section-description',
    <Widget>[Component.text(text)],
  );

  List<Widget> _preset(String name, Object? raw, ComponentSize size) {
    final Map<String, Object?> definition = _object(raw);
    final Object? parent = definition['extends'];
    return <Widget>[
      if (raw is! Map)
        _note(
          huiText('This preset must be a JSON object. Repair it in Code view.'),
        )
      else ...<Widget>[
        Field(
          label: huiText('Extends'),
          forId: 'presets-extends',
          tip: huiText('Optional parent preset name in this collection.'),
          child: TextInput(
            id: 'presets-extends',
            value: parent is String ? parent : '',
            size: size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            onChanged: (String value) => _definition(name, 'preset parent', (
              Map<String, Object?> definition,
            ) {
              if (value.trim().isEmpty) {
                definition.remove('extends');
              } else {
                definition['extends'] = value.trim();
              }
            }),
          ),
        ),
        if (definition['values'] != null && definition['values'] is! Map)
          _note(
            huiText(
              'Preset values must be a JSON object. Repair them in Code view.',
            ),
          )
        else
          ExtrasEditor(
            key: ValueKey<String>('preset:$_collection:$name'),
            title: huiText('Preset values'),
            extensionKeys: false,
            extras: _object(definition['values']),
            onChanged: (String label, Map<String, Object?> values) =>
                _definition(
                  name,
                  label,
                  (Map<String, Object?> definition) =>
                      definition['values'] = values,
                ),
          ),
      ],
      ActionRow(
        dense: true,
        secondary: <RowAction>[
          RowAction(
            huiText('Remove preset'),
            () => _remove(name),
            icon: ArcaneIcon.trash(),
            destructive: true,
          ),
        ],
      ),
    ];
  }
}
