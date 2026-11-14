/// PlaceholderAPI helper for the toggle condition field.
///
/// The catalogue ships Gloss's own three placeholders plus a handful of common
/// PAPI examples. Picking one replaces the condition outright, because the
/// condition is compared whole with `equalsIgnoreCase`: there is no expression
/// language to append to.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../services/catalogs.dart';
import '../../ui/menu.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';

class PlaceholderPicker extends StatelessWidget {
  const PlaceholderPicker({
    required this.catalogs,
    required this.onPicked,
    this.label,
    super.key,
  });

  final HuiCatalogs catalogs;

  /// Receives the raw placeholder token, e.g. `%gloss_available%`.
  final void Function(String placeholder) onPicked;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final List<(String, String)> entries = catalogs.placeholders;
    // It sits on a section's title line beside Add, so its note is a line in
    // the menu rather than a second (i) there.
    return AppMenu(
      icon: ArcaneIcon.hash(),
      iconLabel: label ?? huiText('Placeholders'),
      alignEnd: true,
      entries: <AppMenuEntry>[
        AppMenuEntry.heading(huiText('Placeholders')),
        AppMenuEntry(
          huiText(
            'Anything outside Gloss needs PlaceholderAPI installed on the '
            'server. Without it the condition string is compared literally.',
          ),
          null,
        ),
        if (entries.isEmpty)
          AppMenuEntry(
            huiText(
              'The placeholder catalogue could not be loaded. Type the '
              'placeholder by hand, including the surrounding % signs.',
            ),
            null,
          )
        else
          for (final (String, String) entry in entries)
            AppMenuEntry(
              entry.$1,
              () => onPicked(entry.$1),
              description: entry.$2,
            ),
      ],
    );
  }
}
