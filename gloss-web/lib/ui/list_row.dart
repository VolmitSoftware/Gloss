/// A selectable row of a list (rail items, an inspector's list of lines): a
/// ghost button filling the row and named by the item, `aria-current` while
/// selected, then optional trailing controls (an issue dot, the row's
/// `AppMenu`). The selected row takes the accent fill. Height from the part
/// it sits in ([DensityScope]).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show EventCallback;

import 'density.dart';

class ListRow extends StatelessWidget {
  const ListRow({
    required this.label,
    required this.selected,
    required this.onSelect,
    this.icon,
    this.trailing = const <Widget>[],
    this.attributes = const <String, String>{},
    this.rowAttributes = const <String, String>{},
    this.events,
    super.key,
  });

  final String label;
  final bool selected;
  final void Function() onSelect;
  final ArcaneGlyph? icon;

  /// Dense controls after the select button.
  final List<Widget> trailing;

  /// On the select button.
  final Map<String, String> attributes;

  /// On the row.
  final Map<String, String> rowAttributes;

  /// On the row (a drag handle's `pointerdown`).
  final Map<String, EventCallback>? events;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: selected ? 'ui-list-row is-selected' : 'ui-list-row',
    attributes: rowAttributes,
    events: events,
    <Widget>[
      Button(
        variant: ButtonVariant.ghost,
        size: buttonSize(DensityScope.of(context)),
        icon: icon?.sized(controlIconSize),
        label: label,
        styles: const ArcaneStyleData(justifyContent: JustifyContent.flexStart),
        attributes: <String, String>{
          'data-ui-row': '',
          if (selected) 'aria-current': 'true',
          ...attributes,
        },
        onPressed: onSelect,
      ),
      ...trailing,
    ],
  );
}
