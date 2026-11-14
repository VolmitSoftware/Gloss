/// Editor for the keys of one JSON object, one row of text per key.
///
/// By default it shows the unknown keys the codec preserves:
/// `json_codec.dart:112-129` deep-copies every key Gloss does not recognise
/// and merges it back on export, which is what makes an import/export round
/// trip lossless. With [ExtrasEditor.extensionKeys] off it edits a known
/// object's keys as raw values instead.
///
/// The group is a kit [AppSection], closed at first; the section owns its
/// open state, so the rebuild on every keystroke keeps it as the reader left
/// it. An unknown-key editor is Advanced only.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/json_value.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

int _nextExtrasId = 0;

class ExtrasEditor extends StatefulWidget {
  const ExtrasEditor({
    required this.title,
    required this.extras,
    required this.onChanged,
    this.knownKeys = const <String>{},
    this.extensionKeys = true,
    this.stringsOnly = false,
    this.advancedOnly,
    this.open = false,
    super.key,
  });

  /// Names the object the keys hang off (`Menu`, `Icon`, `Action`): the
  /// section title, and the stem of the undo labels this editor commits.
  final String title;

  /// The live extras map. Insertion order is the export order, so edits keep it.
  final Map<String, dynamic> extras;

  final void Function(String label, Map<String, dynamic> next) onChanged;
  final Set<String> knownKeys;
  final bool extensionKeys;
  final bool stringsOnly;

  /// Hides the editor in Simple mode. Null follows [extensionKeys]: unknown
  /// keys are Advanced only, a known object's values show in both modes.
  final bool? advancedOnly;

  /// Starts open: for a form whose whole content is this list (a strings
  /// document's entries, a names category).
  final bool open;

  @override
  State<ExtrasEditor> createState() => _ExtrasEditorState();
}

class _ExtrasEditorState extends State<ExtrasEditor> {
  final String _id = 'extras-${_nextExtrasId++}';

  /// Text the user is typing, per key. Held locally so an entry that does not
  /// parse yet stays on screen instead of being reverted by the next rebuild.
  final Map<String, String> _drafts = <String, String>{};
  final Map<String, JsonParseResult> _errors = <String, JsonParseResult>{};

  Iterable<String> get _unknownKeys => component.extras.keys.where(
    (String key) => !component.knownKeys.contains(key),
  );

  String _newKey = '';
  String Function()? _newKeyError;

  @override
  void didUpdateWidget(ExtrasEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Undo, redo and a document swap all rewrite extras behind us. Drop the
    // draft for any key whose committed value moved, and for keys that left.
    final Map<String, dynamic> next = component.extras;
    _drafts.removeWhere((String key, String draft) {
      if (!next.containsKey(key)) return true;
      final Object? was = oldComponent.extras[key];
      final Object? now = next[key];
      return formatJsonValue(was) != formatJsonValue(now);
    });
    _errors.removeWhere(
      (String key, JsonParseResult _) => !_drafts.containsKey(key),
    );
  }

  String _valueText(String key) =>
      _drafts[key] ??
      (component.stringsOnly
          ? '${component.extras[key] ?? ''}'
          : formatJsonValue(component.extras[key]));

  void _commit(String label, Map<String, dynamic> next) =>
      component.onChanged('${component.title.toLowerCase()} $label', next);

  void _editValue(String key, String raw) {
    _drafts[key] = raw;
    final JsonParseResult parsed = component.stringsOnly
        ? JsonParseResult.value(raw)
        : parseJsonValue(raw);
    setState(() {
      if (parsed.ok) {
        _errors.remove(key);
      } else {
        _errors[key] = parsed;
      }
    });
    if (!parsed.ok) return;
    final Map<String, dynamic> next = Map<String, dynamic>.of(component.extras);
    next[key] = parsed.value;
    _commit('extra $key', next);
  }

  void _remove(String key) {
    _drafts.remove(key);
    _errors.remove(key);
    final Map<String, dynamic> next = Map<String, dynamic>.of(component.extras)
      ..remove(key);
    _commit('remove extra $key', next);
  }

  void _add() {
    final String key = _newKey.trim();
    if (key.isEmpty) {
      setState(() => _newKeyError = () => huiText('Give the key a name.'));
      return;
    }
    if (component.extras.containsKey(key) ||
        component.knownKeys.contains(key)) {
      setState(
        () => _newKeyError = () =>
            huiText('{key} is already here.', <String, Object?>{'key': key}),
      );
      return;
    }
    setState(() {
      _newKey = '';
      _newKeyError = null;
    });
    final Map<String, dynamic> next = Map<String, dynamic>.of(component.extras);
    next[key] = '';
    _commit('add extra $key', next);
  }

  @override
  Widget build(BuildContext context) {
    final List<String> keys = _unknownKeys.toList();
    final ComponentSize size = controlSize(DensityScope.of(context));
    final Widget section = AppSection(
      title: component.title,
      count: keys.length,
      open: component.open,
      // Hook for the browser scripts that edit a key by its label.
      classes: 'hui-extras',
      tip: component.extensionKeys
          ? huiText(
              'Gloss ignores keys it does not recognise, so nothing here changes '
              'anything in game. The editor keeps them and writes them back on '
              'export, which is why an imported file never loses what it arrived '
              'with.',
            )
          : null,
      children: <Widget>[
        if (keys.isEmpty)
          ArcaneFieldDescription(
            component.extensionKeys
                ? huiText('No extra keys on this object.')
                : huiText('Nothing here yet'),
          )
        else
          for (int index = 0; index < keys.length; index++)
            _row(keys[index], '$_id-$index', size),
        _addRow(size),
      ],
    );
    return component.advancedOnly ?? component.extensionKeys
        ? AdvancedOnly(child: section)
        : section;
  }

  Widget _row(String key, String id, ComponentSize size) =>
      dom.div(classes: 'hui-extras-row', <Widget>[
        Field(
          label: key,
          forId: id,
          error: _errors[key]?.error,
          tip: component.stringsOnly
              ? null
              : huiText(
                  'Bare text is a string; quote it to keep a literal like true or 7.',
                ),
          trailing: AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText("Remove {key}", <String, Object?>{'key': key}),
            onPressed: () => _remove(key),
          ),
          child: TextInput(
            id: id,
            value: _valueText(key),
            size: size,
            fullWidth: true,
            placeholder: huiText('value'),
            onChanged: (String value) => _editValue(key, value),
            attributes: const <String, String>{
              'autocomplete': 'off',
              'spellcheck': 'false',
            },
          ),
        ),
      ]);

  Widget _addRow(ComponentSize size) => Field(
    label: huiText('Add a key'),
    forId: '$_id-new',
    error: _newKeyError?.call(),
    trailing: AppIconButton(
      icon: ArcaneIcon.plus(),
      label: huiText('Add'),
      onPressed: _add,
    ),
    child: TextInput(
      id: '$_id-new',
      value: _newKey,
      size: size,
      fullWidth: true,
      placeholder: huiText('myPluginKey'),
      onChanged: (String value) {
        _newKey = value;
        if (_newKeyError != null) {
          setState(() => _newKeyError = null);
        }
      },
      onSubmitted: (String _) => _add(),
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );
}
