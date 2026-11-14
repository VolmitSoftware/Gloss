/// The leaderboard form, in the centre: what is counted, how many rows, the
/// sort and the reset period, and how a row reads. A catalog form like
/// Names or Glyphs. Each control writes one key, so keys this form does not
/// show stay in the file.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../inspector/catalog_form.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';

class LeaderboardView extends StatelessWidget {
  const LeaderboardView({required this.store, super.key});

  final EditorStore store;

  void _edit(String label, void Function(GlossLeaderboardDoc doc) edit) =>
      store.mutateGloss(label, (GlossDoc doc) {
        if (doc is GlossLeaderboardDoc) edit(doc);
      });

  @override
  Widget build(BuildContext context) =>
      CatalogFormHost(store: store, builder: _form);

  Widget _text(
    BuildContext context, {
    required String id,
    required String value,
    required String placeholder,
    required void Function(String value) onChanged,
  }) => TextInput(
    id: id,
    value: value,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    placeholder: placeholder,
    onChanged: onChanged,
    attributes: const <String, String>{
      'autocomplete': 'off',
      'spellcheck': 'false',
    },
  );

  Widget _form(BuildContext context) {
    final GlossDoc? doc = store.glossDoc;
    if (doc is! GlossLeaderboardDoc) return const dom.div(<Widget>[]);
    final String type = doc.sourceType.toLowerCase();
    return CatalogForm(
      kind: huiText('Leaderboard'),
      title: store.menuId,
      revision: doc.revision,
      kindClass: 'is-leaderboard',
      children: <Widget>[
        AppSection(
          title: huiText('Source'),
          memoryKey: 'leaderboard.source',
          children: <Widget>[
            Field(
              label: huiText('Counts'),
              child: ToggleChoice<String>(
                label: huiText('Counts'),
                value: glossLeaderboardSourceTypes.contains(type)
                    ? type
                    : 'statistic',
                onChanged: (String value) => _edit(
                  'source type',
                  (GlossLeaderboardDoc d) => d.sourceType = value,
                ),
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('statistic', huiText('Statistic')),
                  ChoiceOption<String>('papi', huiText('Placeholder')),
                  ChoiceOption<String>('metric', huiText('Metric')),
                ],
              ),
            ),
            Field(
              label: switch (type) {
                'papi' => huiText('Placeholder'),
                'metric' => huiText('Metric key'),
                _ => huiText('Statistic'),
              },
              forId: 'leaderboard-source',
              tip: huiText(
                'A Bukkit statistic such as PLAY_ONE_MINUTE, a PlaceholderAPI token, or a Gloss metric key.',
              ),
              child: _text(
                context,
                id: 'leaderboard-source',
                value: doc.sourceValue,
                placeholder: type == 'papi'
                    ? '%player_level%'
                    : 'PLAY_ONE_MINUTE',
                onChanged: (String value) => _edit(
                  'source',
                  (GlossLeaderboardDoc d) => d.sourceValue = value,
                ),
              ),
            ),
          ],
        ),
        AppSection(
          title: huiText('Ranking'),
          memoryKey: 'leaderboard.ranking',
          children: <Widget>[
            Field(
              label: huiText('Rows'),
              forId: 'leaderboard-size',
              child: NumberField(
                id: 'leaderboard-size',
                value: doc.size.toDouble(),
                integer: true,
                min: 1,
                max: glossLeaderboardMaxSize.toDouble(),
                step: 1,
                onChanged: (double value) => _edit(
                  'rows',
                  (GlossLeaderboardDoc d) => d.size = value.round(),
                ),
              ),
            ),
            Field(
              label: huiText('Order'),
              child: ToggleChoice<String>(
                label: huiText('Order'),
                value: glossLeaderboardOrders.contains(doc.order)
                    ? doc.order
                    : 'desc',
                onChanged: (String value) =>
                    _edit('order', (GlossLeaderboardDoc d) => d.order = value),
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('desc', huiText('Highest first')),
                  ChoiceOption<String>('asc', huiText('Lowest first')),
                ],
              ),
            ),
            Field(
              label: huiText('Resets'),
              child: ToggleChoice<String>(
                label: huiText('Resets'),
                value: glossLeaderboardResets.contains(doc.reset)
                    ? doc.reset
                    : 'never',
                onChanged: (String value) =>
                    _edit('reset', (GlossLeaderboardDoc d) => d.reset = value),
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('never', huiText('Never')),
                  ChoiceOption<String>('daily', huiText('Daily')),
                  ChoiceOption<String>('weekly', huiText('Weekly')),
                  ChoiceOption<String>('monthly', huiText('Monthly')),
                ],
              ),
            ),
            SwitchField(
              label: huiText('Include offline players'),
              value: doc.includeOffline,
              onChanged: (bool value) => _edit(
                'include offline',
                (GlossLeaderboardDoc d) => d.includeOffline = value,
              ),
            ),
          ],
        ),
        AppSection(
          title: huiText('Row text'),
          memoryKey: 'leaderboard.format',
          children: <Widget>[
            Field(
              label: huiText('Value'),
              forId: 'leaderboard-value-format',
              child: _text(
                context,
                id: 'leaderboard-value-format',
                value: doc.valueFormat,
                placeholder: '{{ value }}',
                onChanged: (String value) => _edit(
                  'value format',
                  (GlossLeaderboardDoc d) => d.valueFormat = value,
                ),
              ),
            ),
            Field(
              label: huiText('Name'),
              forId: 'leaderboard-name-format',
              child: _text(
                context,
                id: 'leaderboard-name-format',
                value: doc.nameFormat,
                placeholder: '{{ name }}',
                onChanged: (String value) => _edit(
                  'name format',
                  (GlossLeaderboardDoc d) => d.nameFormat = value,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
