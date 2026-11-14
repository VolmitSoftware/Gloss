library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/real_drop_labels.dart';
import '../../logic/validation.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/technical_input.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// `labels.names`: one row per material, in file order, each holding the
/// `{type}` text that material's stack label renders with.
///
/// A material is fixed once added, so typing never renames one entry into
/// another's key; changing a material is a remove and an add. A new material is
/// trimmed and upper-cased the way the server reads it, and starts on the Title
/// Case name the label already shows.
class RealDropLabelNamesEditor extends StatefulWidget {
  const RealDropLabelNamesEditor({
    required this.names,
    required this.issues,
    required this.onChanged,
    super.key,
  });

  final Map<String, String> names;

  /// Every issue under `labels.names`, already mapped to the selected
  /// presentation.
  final List<HuiIssue> issues;

  final void Function(String label, Map<String, String> next) onChanged;

  @override
  State<RealDropLabelNamesEditor> createState() =>
      _RealDropLabelNamesEditorState();
}

class _RealDropLabelNamesEditorState extends State<RealDropLabelNamesEditor> {
  static const String _newId = 'real-drops-label-names-new';

  String _newMaterial = '';
  String Function()? _newMaterialError;

  void _edit(String material, String name) {
    final Map<String, String> next = Map<String, String>.of(component.names);
    next[material] = name;
    component.onChanged('drop label name', next);
  }

  void _remove(String material) {
    final Map<String, String> next = Map<String, String>.of(component.names)
      ..remove(material);
    component.onChanged('remove drop label name', next);
  }

  void _add() {
    final String material = _newMaterial.trim().toUpperCase();
    if (material.isEmpty) {
      setState(() => _newMaterialError = () => huiText('Enter a material.'));
      return;
    }
    if (component.names.keys.any(
      (String key) => key.trim().toUpperCase() == material,
    )) {
      setState(
        () => _newMaterialError = () => huiText(
          '{key} is already here.',
          <String, Object?>{'key': material},
        ),
      );
      return;
    }
    setState(() {
      _newMaterial = '';
      _newMaterialError = null;
    });
    final Map<String, String> next = Map<String, String>.of(component.names);
    next[material] = glossDropMaterialName(material);
    component.onChanged('add drop label name', next);
  }

  @override
  Widget build(BuildContext context) {
    final List<HuiIssue> listIssues = distinctIssues(<HuiIssue>[
      for (final HuiIssue issue in component.issues)
        if (issue.path.endsWith('.names')) issue,
    ]);
    return Component.fragment(<Widget>[
      dom.h3(classes: 'ui-section-title', <Widget>[
        TipLabel(
          label: Text(huiText('Names')),
          docKey: 'realDrops.labels.names',
        ),
      ]),
      for (final HuiIssue issue in listIssues) issueNote(issue),
      if (component.names.isEmpty)
        ArcaneFieldDescription(
          huiText('No names. Every material shows its own name in Title Case.'),
        )
      else
        for (final String material in component.names.keys.toList())
          _row(material),
      _addRow(),
    ]);
  }

  Widget _row(String material) => Field(
    label: material,
    problems: <HuiIssue>[
      for (final HuiIssue issue in component.issues)
        if (issue.path.endsWith('.names.$material')) issue,
    ],
    trailing: AppIconButton(
      icon: ArcaneIcon.trash2(),
      label: huiText('Remove {key}', <String, Object?>{'key': material}),
      tipAtEnd: true,
      onPressed: () => _remove(material),
    ),
    child: TextInput(
      value: component.names[material] ?? '',
      size: controlSize(Density.dense),
      fullWidth: true,
      placeholder: '&7Cobble',
      styles: technicalInputStyles,
      attributes: <String, String>{
        ...technicalInputAttributes,
        'aria-label': huiText('Name for {key}', <String, Object?>{
          'key': material,
        }),
      },
      onChanged: (String value) => _edit(material, value),
    ),
  );

  Widget _addRow() => Field(
    label: huiText('Add material'),
    forId: _newId,
    error: _newMaterialError?.call(),
    child: ActionRow(
      dense: true,
      leading: <Widget>[
        TextInput(
          id: _newId,
          value: _newMaterial,
          size: controlSize(Density.dense),
          fullWidth: true,
          placeholder: 'COBBLESTONE',
          onChanged: (String value) {
            _newMaterial = value;
            if (_newMaterialError != null) {
              setState(() => _newMaterialError = null);
            }
          },
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
      ],
      secondary: <RowAction>[
        RowAction(huiText('Add'), _add, icon: ArcaneIcon.plus()),
      ],
    ),
  );
}
