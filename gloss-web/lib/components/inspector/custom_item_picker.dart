/// `customItem` icon editor: provider, the provider's own id, and stack count.
///
/// The editor is a static page with no connection to the server, so it can
/// never confirm an id. Every field here stays free text, the catalog is only
/// ever an aid, and nothing an unrecognised id produces is worse than an info.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/number_field.dart';
import '../../ui/picker.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class CustomItemIconEditor extends StatelessWidget {
  const CustomItemIconEditor({
    required this.icon,
    required this.catalogs,
    required this.onChanged,
    this.issues = const <HuiIssue>[],
    this.loading = false,
    this.idPrefix = 'hui-icon-custom',
    super.key,
  });

  final HuiCustomItemIcon icon;
  final HuiCatalogs catalogs;

  /// Called with a mutation label and the edited copy.
  final void Function(String label, HuiCustomItemIcon icon) onChanged;
  final List<HuiIssue> issues;

  /// True until the catalogs have loaded.
  final bool loading;

  /// Unique per icon slot: a toggle shows two of these at once.
  final String idPrefix;

  HuiCustomItemCatalog get _catalog => catalogs.customItems;

  HuiItemProviderInfo? get _info => huiItemProviderInfo[icon.provider];

  List<HuiIssue> _issuesFor(String suffix) =>
      issues.where((HuiIssue issue) => issue.path.endsWith(suffix)).toList();

  HuiCustomItemIcon _with({String? provider, String? item, int? count}) =>
      HuiCustomItemIcon(
        provider ?? icon.provider,
        item ?? icon.item,
        count ?? icon.count,
        icon.style?.copy(),
      )..extras = huiDeepCopyMap(icon.extras);

  /// Auto-detect first, then the adapters in declaration order. A provider the
  /// file already names but this build has no adapter for is kept as an option
  /// so opening the inspector cannot silently rewrite it.
  List<ArcaneSelectOption> get _providerOptions {
    final List<ArcaneSelectOption> options = <ArcaneSelectOption>[
      ArcaneSelectOption(
        label: huiText('Auto-detect'),
        value: huiAutoItemProvider,
      ),
      for (final String provider in huiCustomItemProviders)
        ArcaneSelectOption(
          label: huiItemProviderInfo[provider] == null
              ? provider
              : huiText(huiItemProviderInfo[provider]!.label),
          value: provider,
        ),
    ];
    if (icon.provider.isNotEmpty &&
        icon.provider != huiAutoItemProvider &&
        !huiCustomItemProviders.contains(icon.provider)) {
      options.add(
        ArcaneSelectOption(
          label: huiText("{provider} (unknown)", <String, Object?>{
            'provider': icon.provider,
          }),
          value: icon.provider,
        ),
      );
    }
    return options;
  }

  String? _textureFor(String id) {
    final String? material = _catalog.entry(icon.provider, id)?.material;
    return material == null ? null : catalogs.textureFor(material);
  }

  /// Entries for the current provider only: the same bare id can exist in two
  /// plugins, and picking the wrong one resolves to the wrong item. The
  /// current id leads the unfiltered list.
  List<PickerOption> _search(String query) {
    final List<PickerOption> found = <PickerOption>[
      for (final CustomItemEntry entry in _catalog.search(
        query,
        provider: icon.provider,
        limit: 60,
      ))
        PickerOption(
          entry.id,
          image: entry.material == null
              ? null
              : catalogs.textureFor(entry.material!),
          description: <String>[
            if (entry.name != null) entry.name!,
            entry.provider,
          ].join(' · '),
        ),
    ];
    if (query.trim().isEmpty &&
        icon.item.isNotEmpty &&
        !found.any((PickerOption option) => option.value == icon.item)) {
      found.insert(0, PickerOption(icon.item, image: _textureFor(icon.item)));
    }
    return found;
  }

  /// What the exported catalog can and cannot tell about an id.
  String get _catalogTip {
    if (_catalog.isEmpty) {
      return huiText(
        'No catalog loaded, so ids cannot be checked here. Run '
        '/gloss item export on your server and import the file from Settings to get '
        'autocomplete and canvas previews.',
      );
    }
    final String provider = icon.provider == huiAutoItemProvider
        ? ''
        : huiText(' for {provider}', <String, Object?>{
            'provider': _info?.label ?? icon.provider,
          });
    return huiPlural(
      'custom-item.catalog-count',
      _catalog.countFor(icon.provider),
      oneEnglish:
          '{count} item in the catalog exported from your server{provider}. '
          'Ids missing from it are not errors - only the server can confirm one.',
      otherEnglish:
          '{count} items in the catalog exported from your server{provider}. '
          'Ids missing from it are not errors - only the server can confirm one.',
      arguments: <String, Object?>{'provider': provider},
    );
  }

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    Field(
      label: huiText('Provider'),
      forId: '$idPrefix-provider',
      docKey: 'icon.customItem.provider',
      problems: _issuesFor('.provider'),
      child: ArcaneSelect(
        id: '$idPrefix-provider',
        value: icon.provider,
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        options: _providerOptions,
        onChanged: (String value) =>
            onChanged('item provider', _with(provider: value)),
      ),
    ),
    Field(
      label: huiText('Item id'),
      required: true,
      forId: '$idPrefix-item',
      tip: _catalogTip,
      hint: _info == null
          ? null
          : huiText('{format}. Example: {example}', <String, Object?>{
              'format': huiText(_info!.idFormat),
              'example': _info!.example,
            }),
      problems: _issuesFor('.item'),
      child: Picker(
        id: '$idPrefix-item',
        value: icon.item,
        placeholder: _info?.example ?? 'myitems:ruby',
        searchPlaceholder: huiText('Search the exported catalog'),
        emptyMessage: loading
            ? huiText('Loading')
            : huiText(
                'No match in the exported catalog. The id is still used as typed.',
              ),
        search: _search,
        onChanged: (String value) =>
            onChanged('custom item id', _with(item: value)),
      ),
    ),
    Field(
      label: huiText('Count'),
      forId: '$idPrefix-count',
      docKey: 'icon.customItem.count',
      problems: _issuesFor('.count'),
      child: NumberField(
        id: '$idPrefix-count',
        value: icon.count.toDouble(),
        min: 1,
        max: 99,
        step: 1,
        integer: true,
        onChanged: (double value) =>
            onChanged('custom item count', _with(count: value.round())),
      ),
    ),
  ]);
}
