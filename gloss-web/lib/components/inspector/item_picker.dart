/// `item` icon editor: material key, stack count and custom model value.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../ui/field.dart';
import '../../ui/number_field.dart';
import '../../ui/picker.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ItemIconEditor extends StatelessWidget {
  const ItemIconEditor({
    required this.icon,
    required this.catalogs,
    required this.onChanged,
    this.issues = const <HuiIssue>[],
    this.loading = false,
    this.idPrefix = 'hui-icon-item',
    super.key,
  });

  final HuiItemIcon icon;
  final HuiCatalogs catalogs;

  /// Called with a mutation label and the edited copy.
  final void Function(String label, HuiItemIcon icon) onChanged;
  final List<HuiIssue> issues;

  /// True until the material catalog has loaded.
  final bool loading;

  /// Unique per icon slot: a toggle shows two of these at once.
  final String idPrefix;

  List<HuiIssue> _issuesFor(String suffix) =>
      issues.where((HuiIssue issue) => issue.path.endsWith(suffix)).toList();

  HuiItemIcon _with({String? item, int? count, int? customModelValue}) =>
      HuiItemIcon(
        item ?? icon.item,
        count ?? icon.count,
        customModelValue ?? icon.customModelValue,
        icon.style?.copy(),
      )..extras = huiDeepCopyMap(icon.extras);

  /// The current material leads the unfiltered list with its sprite.
  List<PickerOption> _search(String query) {
    final List<PickerOption> found = <PickerOption>[
      for (final MaterialEntry entry in catalogs.searchMaterials(query))
        PickerOption(entry.key, image: entry.texture),
    ];
    if (query.trim().isEmpty &&
        icon.item.isNotEmpty &&
        !found.any((PickerOption option) => option.value == icon.item)) {
      found.insert(
        0,
        PickerOption(icon.item, image: catalogs.textureFor(icon.item)),
      );
    }
    return found;
  }

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    Field(
      label: huiText('Material'),
      required: true,
      forId: '$idPrefix-material',
      tip: huiText('Lowercase registry key. An unknown one blocks the open.'),
      problems: _issuesFor('.item'),
      child: Picker(
        id: '$idPrefix-material',
        value: icon.item,
        placeholder: huiText('diamond_sword'),
        searchPlaceholder: huiText('Search 1691 materials'),
        emptyMessage: loading ? huiText('Loading') : null,
        lowercase: true,
        search: _search,
        onChanged: (String value) =>
            onChanged('item material', _with(item: value)),
      ),
    ),
    Field(
      label: huiText('Count'),
      forId: '$idPrefix-count',
      tip: huiText(
        'A count above 1 adds a bold white number under the item and '
        'lifts the item slightly.',
      ),
      problems: _issuesFor('.count'),
      child: NumberField(
        id: '$idPrefix-count',
        value: icon.count.toDouble(),
        min: 1,
        max: 99,
        step: 1,
        integer: true,
        onChanged: (double value) =>
            onChanged('item count', _with(count: value.round())),
      ),
    ),
    Field(
      label: huiText('Model value'),
      forId: '$idPrefix-model',
      tip: huiText(
        'The model value is exported as customModelValue. The old '
        'editor wrote customModelData, which the plugin ignores; '
        'importing such a file moves the value over.',
      ),
      problems: _issuesFor('.customModelValue'),
      child: NumberField(
        id: '$idPrefix-model',
        value: icon.customModelValue.toDouble(),
        min: 0,
        step: 1,
        integer: true,
        onChanged: (double value) => onChanged(
          'item custom model value',
          _with(customModelValue: value.round()),
        ),
      ),
    ),
  ]);
}
