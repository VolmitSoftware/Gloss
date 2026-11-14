/// The events that submit a surface (`on`): join, world and server changes,
/// and intervals, each with an optional delay and viewer condition. One
/// section per subscription; the condition is an expression, so it shows in
/// Advanced only.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../l10n/hui_localizations.dart';
import '../../logic/validation.dart';
import '../../model/gloss_surface.dart';
import '../../model/gloss_surface_subscription.dart';
import '../../state/editor_store.dart';
import '../../ui/affixed.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';

class SurfaceSubscriptionsEditor extends StatelessWidget {
  const SurfaceSubscriptionsEditor({required this.store, super.key});
  final EditorStore store;

  void _edit(int index, void Function(GlossSurfaceSubscription entry) update) =>
      store.mutateSurface('surface subscription', (GlossSurfaceDoc next) {
        update(next.on![index]);
      });

  List<HuiIssue> _issuesAt(String path) =>
      store.issues.where((HuiIssue issue) => issue.path == path).toList();

  List<HuiIssue> _issuesUnder(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  static String _path(int index) =>
      r'$.on['
      '$index]';

  Widget _number(
    BuildContext context,
    int index,
    String key,
    String label,
    String tip,
    int? value,
    void Function(GlossSurfaceSubscription entry, int? value) update,
  ) {
    final String id = 'surface-subscription-$index-$key';
    return Field(
      label: label,
      forId: id,
      tip: tip,
      problems: _issuesUnder('${_path(index)}.$key'),
      child: Affixed(
        suffix: huiText('ticks'),
        child: TextInput(
          id: id,
          value: value?.toString() ?? '',
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          attributes: const <String, String>{
            'inputmode': 'numeric',
            'autocomplete': 'off',
          },
          onChanged: (String source) {
            final int? parsed = int.tryParse(source);
            if (parsed != null || source.trim().isEmpty) {
              _edit(
                index,
                (GlossSurfaceSubscription entry) => update(entry, parsed),
              );
            }
          },
        ),
      ),
    );
  }

  void _move(int from, int to) =>
      store.mutateSurface('move surface subscription', (GlossSurfaceDoc next) {
        final GlossSurfaceSubscription entry = next.on!.removeAt(from);
        next.on!.insert(to, entry);
      });

  Widget _subscription(
    BuildContext context,
    List<GlossSurfaceSubscription> entries,
    int index,
  ) {
    final GlossSurfaceSubscription entry = entries[index];
    final String path = _path(index);
    final String eventId = 'surface-subscription-$index-trigger';
    final String conditionId = 'surface-subscription-$index-when';
    return FieldOwner(
      name: huiText('Subscription {index}', <String, Object?>{
        'index': index + 1,
      }),
      child: AppSection(
        title: huiText('Subscription {index}', <String, Object?>{
          'index': index + 1,
        }),
        memoryKey: 'surface.subscription.$index',
        attributes: <String, String>{'data-surface-subscription': '$index'},
        actions: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.arrowUp(),
            label: huiText('Move up'),
            onPressed: index == 0 ? null : () => _move(index, index - 1),
          ),
          AppIconButton(
            icon: ArcaneIcon.arrowDown(),
            label: huiText('Move down'),
            onPressed: index + 1 == entries.length
                ? null
                : () => _move(index, index + 1),
          ),
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove subscription'),
            onPressed: () => store.mutateSurface(
              'remove surface subscription',
              (GlossSurfaceDoc next) => next.on!.removeAt(index),
            ),
          ),
        ],
        children: <Widget>[
          ..._problemLines(_issuesAt(path)),
          Field(
            label: huiText('Event'),
            forId: eventId,
            problems: _issuesUnder('$path.trigger'),
            child: ArcaneSelect(
              id: eventId,
              value: entry.trigger ?? '',
              size: controlSize(DensityScope.of(context)),
              fullWidth: true,
              options: <ArcaneSelectOption>[
                if (!glossSurfaceSubscriptionTriggers.contains(entry.trigger))
                  ArcaneSelectOption(
                    value: entry.trigger ?? '',
                    label: entry.trigger ?? huiText('Select event'),
                  ),
                for (final String trigger in glossSurfaceSubscriptionTriggers)
                  ArcaneSelectOption(value: trigger, label: trigger),
              ],
              onChanged: (String value) => _edit(
                index,
                (GlossSurfaceSubscription next) => next.setTrigger(value),
              ),
            ),
          ),
          if (entry.trigger == 'interval' || entry.everyTicks != null)
            _number(
              context,
              index,
              'everyTicks',
              huiText('Interval'),
              huiText('Required for interval events, 1–1728000 ticks.'),
              entry.everyTicks,
              (GlossSurfaceSubscription next, int? value) {
                next.everyTicks = value;
                next.extras.remove('everyTicks');
              },
            ),
          _number(
            context,
            index,
            'delayTicks',
            huiText('Delay'),
            huiText('Wait 0–72000 ticks after the event before submitting.'),
            entry.delayTicks,
            (GlossSurfaceSubscription next, int? value) {
              next.delayTicks = value;
              next.extras.remove('delayTicks');
            },
          ),
          AdvancedOnly(
            child: Field(
              label: huiText('Condition'),
              forId: conditionId,
              tip: huiText('Viewer condition evaluated when this event fires.'),
              problems: _issuesUnder('$path.when'),
              child: TextInput(
                id: conditionId,
                value: entry.when ?? '',
                placeholder: 'true',
                size: controlSize(DensityScope.of(context)),
                fullWidth: true,
                styles: technicalInputStyles,
                attributes: technicalInputAttributes,
                onChanged: (String value) =>
                    _edit(index, (GlossSurfaceSubscription next) {
                      next.when = value.isEmpty ? null : value;
                      next.extras.remove('when');
                    }),
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final GlossSurfaceDoc doc = store.surfaceDoc!;
    final List<GlossSurfaceSubscription> entries =
        doc.on ?? const <GlossSurfaceSubscription>[];
    final bool malformed = doc.hasMalformedSubscriptions;
    return AppSection(
      title: huiText('Event subscriptions'),
      memoryKey: 'surface.subscriptions',
      count: malformed ? null : entries.length,
      tip: huiText(
        'World changes run on the backend; server changes run on Velocity. Join and interval events work on both.',
      ),
      actions: <Widget>[
        if (!malformed)
          AppIconButton(
            icon: ArcaneIcon.plus(),
            label: huiText('Add subscription'),
            onPressed: entries.length >= 64
                ? null
                : () => store.mutateSurface('add surface subscription', (
                    GlossSurfaceDoc next,
                  ) {
                    (next.on ??= <GlossSurfaceSubscription>[]).add(
                      GlossSurfaceSubscription(trigger: 'join'),
                    );
                    next.extras.remove('on');
                  }),
          ),
      ],
      children: <Widget>[
        if (malformed) ...<Widget>[
          ..._problemLines(_issuesUnder(r'$.on')),
          ArcaneFieldDescription(
            huiText(
              'Repair event subscriptions in Code view to use these controls.',
            ),
          ),
        ] else ...<Widget>[
          ..._problemLines(_issuesAt(r'$.on')),
          if (entries.isEmpty)
            ArcaneFieldDescription(huiText('No event subscriptions.')),
          for (int index = 0; index < entries.length; index++)
            _subscription(context, entries, index),
        ],
      ],
    );
  }
}

/// Problems that belong to the list or a whole subscription.
List<Widget> _problemLines(List<HuiIssue> issues) => <Widget>[
  for (final HuiIssue issue in distinctIssues(issues)) issueNote(issue),
];
