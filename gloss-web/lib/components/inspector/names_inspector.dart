/// The names catalog's centre form: one group of display names per category.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import 'catalog_form.dart';
import 'extras_editor.dart';

String glossNameCategoryLabel(GlossNameCategory category) => switch (category) {
  GlossNameCategory.materials => huiText('Materials'),
  GlossNameCategory.entities => huiText('Entities'),
  GlossNameCategory.worlds => huiText('World'),
  GlossNameCategory.gameModes => huiText('Game modes'),
  GlossNameCategory.dimensions => huiText('Dimensions'),
  GlossNameCategory.damageCauses => huiText('Damage causes'),
  GlossNameCategory.effects => huiText('Effects'),
  GlossNameCategory.groups => huiText('Groups'),
};

class NamesView extends StatelessWidget {
  const NamesView({required this.store, super.key});

  final EditorStore store;

  @override
  Widget build(BuildContext context) =>
      CatalogFormHost(store: store, builder: _content);

  Widget _content(BuildContext context) {
    final GlossDoc? active = store.glossDoc;
    if (active is! GlossNamesDoc) return const Component.fragment(<Widget>[]);
    return CatalogForm(
      kind: huiText('Names'),
      title: store.menuId,
      revision: active.revision,
      kindClass: 'is-names',
      children: <Widget>[
        for (final GlossNameCategory category in GlossNameCategory.values)
          ExtrasEditor(
            open: true,
            title: glossNameCategoryLabel(category),
            extensionKeys: false,
            stringsOnly: true,
            extras: Map<String, Object?>.of(
              active.categories[category] ?? const <String, String>{},
            ),
            onChanged: (String label, Map<String, Object?> next) =>
                store.mutateGloss(label, (GlossDoc doc) {
                  if (doc is GlossNamesDoc) {
                    doc.categories[category] = GlossNamesDoc.readNames(
                      next,
                      '\$.${category.name}',
                    );
                  }
                }),
          ),
      ],
    );
  }
}
