/// `entity` icon editor: the living entity type and its click footprint.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../ui/field.dart';
import '../../ui/number_field.dart';
import '../../ui/picker.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class EntityIconEditor extends StatelessWidget {
  const EntityIconEditor({
    required this.icon,
    required this.onChanged,
    this.issues = const <HuiIssue>[],
    this.idPrefix = 'hui-icon-entity',
    super.key,
  });

  final HuiEntityIcon icon;
  final void Function(String label, HuiEntityIcon icon) onChanged;
  final List<HuiIssue> issues;

  /// Unique per icon slot: a toggle shows two of these at once.
  final String idPrefix;

  List<HuiIssue> _issuesFor(String suffix) =>
      issues.where((HuiIssue issue) => issue.path.endsWith(suffix)).toList();

  HuiEntityIcon _with({String? entity, double? width, double? height}) =>
      HuiEntityIcon(
        entity ?? icon.entity,
        width ?? icon.width,
        height ?? icon.height,
      )..extras = huiDeepCopyMap(icon.extras);

  /// Registry ids as they are: invariant, so no English detail line.
  List<PickerOption> _search(String query) {
    final String normalized = query.trim().toLowerCase();
    return <PickerOption>[
      for (final String entityType in huiSpawnableLivingEntityTypes)
        if (normalized.isEmpty || entityType.contains(normalized))
          PickerOption(entityType),
    ];
  }

  Widget _size({
    required String label,
    required String key,
    required double value,
    required HuiEntityIcon Function(double) edit,
  }) => Field(
    label: label,
    forId: '$idPrefix-$key',
    docKey: 'icon.entity.$key',
    problems: _issuesFor('.$key'),
    child: NumberField(
      id: '$idPrefix-$key',
      value: value,
      min: 0.01,
      max: 64,
      step: 0.05,
      suffix: huiText('blocks'),
      onChanged: (double next) => onChanged('entity $key', edit(next)),
    ),
  );

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    Field(
      label: huiText('Entity type'),
      required: true,
      forId: '$idPrefix-type',
      docKey: 'icon.entity.entity',
      problems: _issuesFor('.entity'),
      child: Picker(
        id: '$idPrefix-type',
        value: icon.entity,
        placeholder: huiText('minecraft:parrot'),
        searchPlaceholder: huiText('Search living entities'),
        lowercase: true,
        search: _search,
        onChanged: (String value) =>
            onChanged('entity type', _with(entity: value)),
      ),
    ),
    _size(
      label: huiText('Click width'),
      key: 'width',
      value: icon.width,
      edit: (double value) => _with(width: value),
    ),
    _size(
      label: huiText('Click height'),
      key: 'height',
      value: icon.height,
      edit: (double value) => _with(height: value),
    ),
  ]);
}
