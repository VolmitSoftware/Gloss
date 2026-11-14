/// `block` icon editor: one namespaced block material.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/canvas_scene.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../ui/field.dart';
import '../../ui/picker.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class BlockIconEditor extends StatelessWidget {
  const BlockIconEditor({
    required this.icon,
    required this.catalogs,
    required this.onChanged,
    this.issues = const <HuiIssue>[],
    this.loading = false,
    this.idPrefix = 'hui-icon-block',
    super.key,
  });

  final HuiBlockIcon icon;
  final HuiCatalogs catalogs;
  final void Function(String label, HuiBlockIcon icon) onChanged;
  final List<HuiIssue> issues;

  /// True until the material catalog has loaded.
  final bool loading;

  /// Unique per icon slot: a toggle shows two of these at once.
  final String idPrefix;

  static String _namespaced(String key) =>
      key.contains(':') ? key : 'minecraft:$key';

  String? _textureFor(String key) => catalogs.textureFor(
    key.startsWith('minecraft:') ? key.substring('minecraft:'.length) : key,
  );

  /// Block-like materials only, namespaced the way the file stores them; the
  /// current block leads the unfiltered list with its sprite.
  List<PickerOption> _search(String query) {
    final List<PickerOption> found = catalogs
        .searchMaterials(query, limit: catalogs.materials.length)
        .where((MaterialEntry entry) => huiIsBlockLikeMaterial(entry.key))
        .take(60)
        .map(
          (MaterialEntry entry) =>
              PickerOption(_namespaced(entry.key), image: entry.texture),
        )
        .toList();
    if (query.trim().isEmpty &&
        icon.block.isNotEmpty &&
        !found.any((PickerOption option) => option.value == icon.block)) {
      found.insert(0, PickerOption(icon.block, image: _textureFor(icon.block)));
    }
    return found;
  }

  @override
  Widget build(BuildContext context) => Field(
    label: huiText('Block'),
    required: true,
    forId: '$idPrefix-block',
    docKey: 'icon.block.block',
    problems: issues
        .where((HuiIssue issue) => issue.path.endsWith('.block'))
        .toList(),
    child: Picker(
      id: '$idPrefix-block',
      value: icon.block,
      placeholder: huiText('minecraft:stone'),
      searchPlaceholder: huiText('Search block materials'),
      emptyMessage: loading ? huiText('Loading') : null,
      lowercase: true,
      search: _search,
      onChanged: (String value) => onChanged(
        'block material',
        HuiBlockIcon(value, icon.style?.copy())
          ..extras = huiDeepCopyMap(icon.extras),
      ),
    ),
  );
}
