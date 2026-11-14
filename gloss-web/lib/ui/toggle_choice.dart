/// A two-to-four-way switch (Edit, Preview, Code, Split; Simple or
/// Advanced), drawn as one joined segmented control, and a row of filter
/// chips, both on shadcn's toggle group.
/// Values are mapped to the library's string ids by position, so any value
/// type works. Their height follows the row ([DensityScope]). An option can
/// carry an icon, a tip, or the reason it is unavailable: such an option
/// stays clickable (the caller says why on click) and shows the reason as
/// its tooltip.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import 'density.dart';

/// One choice: its value and visible label.
class ChoiceOption<T> {
  const ChoiceOption(
    this.value,
    this.label, {
    this.icon,
    this.iconOnly = false,
    this.tip,
    this.unavailable,
  });

  final T value;
  final String label;
  final ArcaneGlyph? icon;

  /// Shows the icon alone; [label] stays its accessible name and tooltip.
  final bool iconOnly;

  /// A tooltip ("Code: the document JSON").
  final String? tip;

  /// Why this choice cannot serve the current content; shown as the tooltip
  /// and muted, still clickable.
  final String? unavailable;
}

Widget _optionBody<T>(ChoiceOption<T> option) {
  final String? tip =
      option.unavailable ??
      option.tip ??
      (option.iconOnly ? option.label : null);
  final Widget body = dom.span(
    classes: 'ui-choice${option.unavailable != null ? ' is-unavailable' : ''}',
    attributes: <String, String>{
      'data-choice': option.label,
      if (option.unavailable != null) 'data-unavailable': option.unavailable!,
    },
    <Widget>[
      if (option.icon != null) option.icon!.sized(controlIconSize),
      dom.span(classes: option.iconOnly ? 'ui-sr-only' : null, <Widget>[
        Component.text(option.label),
      ]),
    ],
  );
  return tip == null ? body : ArcaneTooltip(text: tip, child: body);
}

/// Exactly one of [options]. Pressing the chosen one again keeps it.
class ToggleChoice<T> extends StatelessWidget {
  const ToggleChoice({
    required this.label,
    required this.options,
    required this.value,
    required this.onChanged,
    this.id,
    super.key,
  });

  /// The group's accessible name.
  final String label;
  final List<ChoiceOption<T>> options;
  final T value;
  final void Function(T value) onChanged;
  final String? id;

  @override
  Widget build(BuildContext context) {
    final int at = options.indexWhere(
      (ChoiceOption<T> option) => option.value == value,
    );
    return dom.div(
      classes: 'ui-toggle-choice',
      attributes: <String, String>{'role': 'group', 'aria-label': label},
      <Widget>[
        ArcaneToggleGroup(
          id: id,
          items: <ToggleGroupItem>[
            for (int index = 0; index < options.length; index++)
              ToggleGroupItem(
                value: '$index',
                child: _optionBody(options[index]),
              ),
          ],
          value: at < 0 ? null : '$at',
          variant: ToggleGroupVariant.outline,
          joined: true,
          size: toggleSize(DensityScope.of(context)),
          onChanged: (String? picked) {
            // Pressing the chosen option again reports null: keep it.
            final int? index = picked == null ? null : int.tryParse(picked);
            if (index != null && index < options.length) {
              onChanged(options[index].value);
            }
          },
        ),
      ],
    );
  }
}

/// Any number of [options] on or off: filters.
class ToggleChips<T> extends StatelessWidget {
  const ToggleChips({
    required this.label,
    required this.options,
    required this.values,
    required this.onChanged,
    this.joined = false,
    super.key,
  });

  final String label;
  final List<ChoiceOption<T>> options;
  final Set<T> values;
  final void Function(Set<T> values) onChanged;

  /// One segmented control (a frame header's view toggles), not loose chips.
  final bool joined;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'ui-toggle-chips',
    attributes: <String, String>{'role': 'group', 'aria-label': label},
    <Widget>[
      ArcaneToggleGroup.multiple(
        items: <ToggleGroupItem>[
          for (int index = 0; index < options.length; index++)
            ToggleGroupItem(
              value: '$index',
              child: _optionBody(options[index]),
            ),
        ],
        values: <String>{
          for (int index = 0; index < options.length; index++)
            if (values.contains(options[index].value)) '$index',
        },
        variant: ToggleGroupVariant.outline,
        joined: joined,
        size: toggleSize(DensityScope.of(context)),
        onMultiChanged: (Set<String> picked) => onChanged(<T>{
          for (final String id in picked)
            if (int.tryParse(id) case final int index
                when index < options.length)
              options[index].value,
        }),
      ),
    ],
  );
}
