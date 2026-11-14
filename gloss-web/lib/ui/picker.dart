/// A searchable pick from a list that may be long (1691 materials, 1968
/// sounds, every menu in the workspace), on shadcn's combobox. The caller
/// searches: [Picker.search] gets what was typed and returns the matches,
/// of which at most [Picker.maxResults] are drawn. With
/// [Picker.allowCustom] the typed text itself is offered first ("Use
/// "myitems:ruby""), so ids the catalogue does not know still go in. The
/// trigger is `role=combobox` with [Picker.id], for `Field(forId:)`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../l10n/hui_localizations.dart';
import 'density.dart';

/// One choice in a [Picker].
class PickerOption {
  const PickerOption(
    this.value, {
    this.label,
    this.description,
    this.group,
    this.image,
    this.icon,
    this.keywords,
  });

  /// What is stored ("minecraft:stone").
  final String value;

  /// What is shown; [value] when null.
  final String? label;

  /// A muted second line (a display name, the owning plugin).
  final String? description;

  /// A heading the option sits under.
  final String? group;

  /// A picture before the label: a data URI or URL, drawn pixelated.
  final String? image;
  final ArcaneGlyph? icon;
  final List<String>? keywords;
}

int _nextPickerId = 0;

class Picker extends StatefulWidget {
  const Picker({
    required this.value,
    required this.onChanged,
    required this.search,
    this.placeholder,
    this.searchPlaceholder,
    this.emptyMessage,
    this.allowCustom = true,
    this.lowercase = false,
    this.maxResults = 60,
    this.disabled = false,
    this.id,
    super.key,
  });

  /// The stored value; it shows even when [search] does not list it.
  final String value;
  final void Function(String value) onChanged;

  /// The matches for a query ('' when nothing is typed).
  final List<PickerOption> Function(String query) search;
  final String? placeholder;
  final String? searchPlaceholder;
  final String? emptyMessage;

  /// Offers what was typed as a value of its own.
  final bool allowCustom;

  /// Lowercases a typed value (vanilla registry keys must be lowercase;
  /// custom item ids must not be touched).
  final bool lowercase;
  final int maxResults;
  final bool disabled;

  /// The trigger's id.
  final String? id;

  @override
  State<Picker> createState() => _PickerState();
}

class _PickerState extends State<Picker> {
  late final String _ownId = 'ui-picker-${_nextPickerId++}';
  String _query = '';

  String get _id => component.id ?? _ownId;

  String _typed() {
    final String trimmed = _query.trim();
    return component.lowercase ? trimmed.toLowerCase() : trimmed;
  }

  Widget? _leading(PickerOption option) {
    if (option.image == null) return null;
    return dom.img(
      src: option.image!,
      alt: '',
      classes: 'ui-picker-image',
      attributes: const <String, String>{'loading': 'lazy'},
    );
  }

  ComboboxOption<String> _option(PickerOption option) => ComboboxOption<String>(
    value: option.value,
    label: option.label ?? option.value,
    description: option.description,
    group: option.group,
    icon: option.icon,
    keywords: option.keywords,
    leading: _leading(option),
  );

  @override
  Widget build(BuildContext context) {
    final Picker picker = component;
    final List<PickerOption> found = picker
        .search(_query)
        .take(picker.maxResults)
        .toList();
    final String typed = _typed();
    final bool offerTyped =
        picker.allowCustom &&
        typed.isNotEmpty &&
        !found.any((PickerOption option) => option.value == typed);
    final bool showCurrent =
        picker.value.isNotEmpty &&
        typed.isEmpty &&
        !found.any((PickerOption option) => option.value == picker.value);
    return dom.div(classes: 'ui-picker', <Widget>[
      ArcaneCombobox<String>(
        id: '$_id-list',
        triggerId: _id,
        size: comboboxSize(DensityScope.of(context)),
        value: picker.value.isEmpty ? null : picker.value,
        placeholder: picker.placeholder ?? '',
        searchPlaceholder:
            picker.searchPlaceholder ?? huiText('Search catalog'),
        emptyMessage: picker.emptyMessage ?? huiText('No matches'),
        disabled: picker.disabled,
        externalFilter: true,
        onSearch: (String query) {
          if (query != _query) setState(() => _query = query);
        },
        options: <ComboboxOption<String>>[
          if (offerTyped)
            ComboboxOption<String>(
              value: typed,
              label: huiText('Use "{replacement}"', <String, Object?>{
                'replacement': typed,
              }),
            ),
          if (showCurrent)
            ComboboxOption<String>(value: picker.value, label: picker.value),
          for (final PickerOption option in found) _option(option),
        ],
        onChanged: (String? next) {
          if (next != null && next != picker.value) picker.onChanged(next);
        },
      ),
    ]);
  }
}
