/// The MOTD response policy: document rotation, state and icon sets, the
/// independent server links, and each entry's request selection and ping
/// presentation. Expert settings, so the MOTD inspector mounts both parts in
/// Advanced only.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/validation.dart';
import '../../model/gloss_motd.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';

class MotdDocumentPolicies extends StatelessWidget {
  const MotdDocumentPolicies({required this.store, super.key});
  final EditorStore store;
  void _edit(String label, void Function(GlossMotdDoc) edit) =>
      store.mutateMotd(label, edit);

  List<HuiIssue> _issuesAt(String path) =>
      store.issues.where((HuiIssue issue) => issue.path == path).toList();

  List<HuiIssue> _issuesUnder(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) {
    final GlossMotdDoc doc = store.motdDoc!;
    final GlossMotdServerLinks? links = doc.serverLinks;
    final int linkCount = links?.links?.length ?? 0;
    return Component.fragment(<Widget>[
      AppSection(
        title: huiText('Response policy'),
        memoryKey: 'motd.policy',
        tip: huiText(
          'Sequence advances on each handled status request. Time rotation uses the epoch and interval; weighted rotation uses entry weights.',
        ),
        children: <Widget>[
          _choice(
            label: huiText('Rotation mode'),
            value: doc.rotation?.effectiveMode ?? 'weighted',
            options: const <String>['weighted', 'sequence', 'time', 'first'],
            problems: _issuesUnder(r'$.rotation.mode'),
            changed: (String value) => _edit(
              'rotation mode',
              (GlossMotdDoc next) =>
                  (next.rotation ??= GlossMotdRotation()).mode = value,
            ),
          ),
          _number(
            context,
            id: 'motd-rotation-interval',
            label: huiText('Rotation interval seconds'),
            value: doc.rotation?.intervalSeconds,
            placeholder: '60',
            problems: _issuesUnder(r'$.rotation.intervalSeconds'),
            changed: (int? value) => _edit(
              'rotation interval',
              (GlossMotdDoc next) =>
                  (next.rotation ??= GlossMotdRotation()).intervalSeconds =
                      value,
            ),
          ),
          _text(
            context,
            id: 'motd-state',
            label: huiText('Server state'),
            value: doc.state ?? '',
            placeholder: 'normal',
            technical: true,
            problems: _issuesAt(r'$.state'),
            changed: (String value) => _edit(
              'server state',
              (GlossMotdDoc next) => next.state = value.isEmpty ? null : value,
            ),
          ),
          _lines(
            id: 'motd-icons',
            label: huiText('Document icon set'),
            value: doc.icons?.join('\n') ?? '',
            tip: huiText(
              'One 64×64 PNG path per line. Entry icon sets override entry icons, then document icon sets and document icons.',
            ),
            problems: _issuesUnder(r'$.icons'),
            changed: (String value) => _edit(
              'document icons',
              (GlossMotdDoc next) => next.icons = _splitLines(value),
            ),
          ),
        ],
      ),
      AppSection(
        title: huiText('Independent server links'),
        memoryKey: 'motd.serverLinks',
        count: links == null ? null : linkCount,
        tip: huiText(
          'Independent links do not depend on the MOTD feature switch or entry selection. The original link list remains available when this override is removed.',
        ),
        actions: <Widget>[
          if (links != null && linkCount < glossMotdMaxLinks)
            AppIconButton(
              icon: ArcaneIcon.plus(),
              label: huiText('Add independent link'),
              onPressed: () => _edit(
                'add independent link',
                (GlossMotdDoc next) =>
                    (next.serverLinks!.links ??= <GlossMotdLink>[]).add(
                      GlossMotdLink(type: 'website'),
                    ),
              ),
            ),
        ],
        children: <Widget>[
          SwitchField(
            id: 'motd-server-links-independent',
            label: huiText('Manage links independently'),
            value: links != null,
            onChanged: (bool enabled) => _edit(
              'independent links',
              (GlossMotdDoc next) => next.serverLinks = enabled
                  ? GlossMotdServerLinks(
                      links: <GlossMotdLink>[
                        for (final GlossMotdLink link in next.links)
                          link.copy(),
                      ],
                    )
                  : null,
            ),
          ),
          if (links != null) ...<Widget>[
            SwitchField(
              id: 'motd-server-links-enabled',
              label: huiText('Publish server links'),
              value: links.enabled != false,
              onChanged: (bool enabled) => _edit(
                'publish links',
                (GlossMotdDoc next) => next.serverLinks!.enabled = enabled,
              ),
            ),
            ..._problemLines(
              store.issues
                  .where(
                    (HuiIssue issue) =>
                        issue.path.startsWith(r'$.serverLinks') &&
                        !issue.path.startsWith(r'$.serverLinks.links['),
                  )
                  .toList(),
            ),
            for (int index = 0; index < linkCount; index++)
              _link(context, links.links![index], index),
          ],
        ],
      ),
    ]);
  }

  Widget _link(BuildContext context, GlossMotdLink link, int index) {
    void change(void Function(GlossMotdLink) edit) => _edit(
      'independent link',
      (GlossMotdDoc next) => edit(next.serverLinks!.links![index]),
    );
    final String path =
        r'$.serverLinks.links['
        '$index]';
    final String id = 'motd-server-link-$index';
    return FieldOwner(
      name: huiText('Independent link {index}', <String, Object?>{
        'index': index + 1,
      }),
      child: AppSection(
        title: huiText('Independent link {index}', <String, Object?>{
          'index': index + 1,
        }),
        memoryKey: 'motd.serverLink.$index',
        actions: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.arrowUp(),
            label: huiText('Move link up'),
            onPressed: index == 0
                ? null
                : () => _edit('reorder independent links', (GlossMotdDoc next) {
                    final List<GlossMotdLink> entries =
                        next.serverLinks!.links!;
                    entries.insert(index - 1, entries.removeAt(index));
                  }),
          ),
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Delete independent link'),
            onPressed: () => _edit(
              'delete independent link',
              (GlossMotdDoc next) => next.serverLinks!.links!.removeAt(index),
            ),
          ),
        ],
        children: <Widget>[
          ..._problemLines(_issuesAt(path)),
          _select(
            context,
            id: '$id-type',
            label: huiText('Type'),
            value: link.type ?? '',
            options: <String>['', ...glossMotdLinkTypes],
            problems: _issuesUnder('$path.type'),
            changed: (String value) => change(
              (GlossMotdLink next) => next.type = value.isEmpty ? null : value,
            ),
          ),
          _text(
            context,
            id: '$id-label',
            label: huiText('Label'),
            value: link.label ?? '',
            problems: _issuesUnder('$path.label'),
            changed: (String value) => change(
              (GlossMotdLink next) => next.label = value.isEmpty ? null : value,
            ),
          ),
          _text(
            context,
            id: '$id-url',
            label: huiText('Address'),
            value: link.url,
            technical: true,
            problems: _issuesUnder('$path.url'),
            changed: (String value) =>
                change((GlossMotdLink next) => next.url = value),
          ),
        ],
      ),
    );
  }
}

/// One entry's request selection and ping presentation, as flat fields under
/// two headings: the entry's own section already holds them.
class MotdEntryPolicies extends StatelessWidget {
  const MotdEntryPolicies({
    required this.store,
    required this.index,
    super.key,
  });
  final EditorStore store;
  final int index;
  void _edit(String label, void Function(GlossMotdEntry) edit) =>
      store.mutateMotd(label, (GlossMotdDoc doc) => edit(doc.entries[index]));
  void _selector(String label, void Function(GlossMotdSelector) edit) => _edit(
    label,
    (GlossMotdEntry entry) => edit(entry.select ??= GlossMotdSelector()),
  );
  void _counts(String label, void Function(GlossMotdCounts) edit) => _edit(
    label,
    (GlossMotdEntry entry) => edit(entry.counts ??= GlossMotdCounts()),
  );

  List<HuiIssue> _issuesUnder(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  List<HuiIssue> _issuesAt(String path) =>
      store.issues.where((HuiIssue issue) => issue.path == path).toList();

  @override
  Widget build(BuildContext context) {
    final GlossMotdEntry entry = store.motdDoc!.entries[index];
    final GlossMotdSelector selector = entry.select ?? GlossMotdSelector();
    final GlossMotdCounts counts = entry.counts ?? GlossMotdCounts();
    final String select = 'entries[$index].select';
    final String id = 'motd-entry-$index';
    return Component.fragment(<Widget>[
      _heading(
        huiText('Request selection'),
        huiText(
          'Status requests have no authenticated player. Match client-supplied host and protocol metadata, calendar time, state and real online counts.',
        ),
      ),
      _lines(
        id: '$id-hostnames',
        label: huiText('Hostnames'),
        value: selector.hostnames?.join('\n') ?? '',
        problems: _issuesUnder('$select.hostnames'),
        changed: (String value) => _selector(
          'hostnames',
          (GlossMotdSelector next) => next.hostnames = _splitLines(value),
        ),
      ),
      _number(
        context,
        id: '$id-min-protocol',
        label: huiText('Minimum protocol'),
        value: selector.minProtocol,
        problems: _issuesUnder('$select.minProtocol'),
        changed: (int? value) => _selector(
          'minimum protocol',
          (GlossMotdSelector next) => next.minProtocol = value,
        ),
      ),
      _number(
        context,
        id: '$id-max-protocol',
        label: huiText('Maximum protocol'),
        value: selector.maxProtocol,
        problems: _issuesUnder('$select.maxProtocol'),
        changed: (int? value) => _selector(
          'maximum protocol',
          (GlossMotdSelector next) => next.maxProtocol = value,
        ),
      ),
      _text(
        context,
        id: '$id-zone',
        label: huiText('Time zone'),
        value: selector.zone ?? '',
        placeholder: 'UTC',
        technical: true,
        problems: _issuesUnder('$select.zone'),
        changed: (String value) => _selector(
          'time zone',
          (GlossMotdSelector next) => next.zone = value.isEmpty ? null : value,
        ),
      ),
      _text(
        context,
        id: '$id-start',
        label: huiText('Start time'),
        value: selector.startTime ?? '',
        placeholder: '18:00',
        technical: true,
        tip: huiText(
          'Start is inclusive and end is exclusive. Equal times cover the full day. Overnight windows use the weekday after midnight. No selected weekdays means every day.',
        ),
        problems: _issuesUnder('$select.startTime'),
        changed: (String value) => _selector(
          'start time',
          (GlossMotdSelector next) =>
              next.startTime = value.isEmpty ? null : value,
        ),
      ),
      _text(
        context,
        id: '$id-end',
        label: huiText('End time'),
        value: selector.endTime ?? '',
        placeholder: '23:00',
        technical: true,
        problems: _issuesUnder('$select.endTime'),
        changed: (String value) => _selector(
          'end time',
          (GlossMotdSelector next) =>
              next.endTime = value.isEmpty ? null : value,
        ),
      ),
      Field(
        label: huiText('Weekdays'),
        problems: _issuesUnder('$select.days'),
        child: FieldStack(
          children: <Widget>[
            for (final (int day, String label) in <(int, String)>[
              (1, huiText('Monday')),
              (2, huiText('Tuesday')),
              (3, huiText('Wednesday')),
              (4, huiText('Thursday')),
              (5, huiText('Friday')),
              (6, huiText('Saturday')),
              (7, huiText('Sunday')),
            ])
              SwitchField(
                id: '$id-day-$day',
                label: label,
                value: selector.days?.contains(day) ?? false,
                onChanged: (bool enabled) =>
                    _selector('weekdays', (GlossMotdSelector next) {
                      final List<int> days = List<int>.of(
                        next.days ?? const <int>[],
                      );
                      if (enabled) {
                        if (!days.contains(day)) days.add(day);
                      } else {
                        days.removeWhere((int value) => value == day);
                      }
                      next.days = days..sort();
                    }),
              ),
          ],
        ),
      ),
      _lines(
        id: '$id-states',
        label: huiText('Matching states'),
        value: selector.states?.join('\n') ?? '',
        problems: _issuesUnder('$select.states'),
        changed: (String value) => _selector(
          'matching states',
          (GlossMotdSelector next) => next.states = _splitLines(value),
        ),
      ),
      _number(
        context,
        id: '$id-min-online',
        label: huiText('Minimum real online'),
        value: selector.minOnline,
        problems: _issuesUnder('$select.minOnline'),
        changed: (int? value) => _selector(
          'minimum online',
          (GlossMotdSelector next) => next.minOnline = value,
        ),
      ),
      _number(
        context,
        id: '$id-max-online',
        label: huiText('Maximum real online'),
        value: selector.maxOnline,
        problems: _issuesUnder('$select.maxOnline'),
        changed: (int? value) => _selector(
          'maximum online',
          (GlossMotdSelector next) => next.maxOnline = value,
        ),
      ),
      _heading(
        huiText('Ping presentation policy'),
        huiText(
          'Fixed and offset policies override count expressions. Hidden counts and sample controls require Paper or Velocity. These fields do not change admission limits.',
        ),
      ),
      _lines(
        id: '$id-icons',
        label: huiText('Icon set'),
        value: entry.icons?.join('\n') ?? '',
        problems: _issuesUnder('entries[$index].icons'),
        changed: (String value) => _edit(
          'entry icons',
          (GlossMotdEntry next) => next.icons = _splitLines(value),
        ),
      ),
      _choice(
        label: huiText('Sample mode'),
        value: entry.sampleMode ?? '',
        options: const <String>['', 'inherit', 'replace', 'hide'],
        problems: _issuesAt('entries[$index].sampleMode'),
        changed: (String value) => _edit(
          'sample mode',
          (GlossMotdEntry next) =>
              next.sampleMode = value.isEmpty ? null : value,
        ),
      ),
      _choice(
        label: huiText('Online count mode'),
        value: counts.onlineMode ?? 'inherit',
        options: const <String>['inherit', 'fixed', 'offset'],
        problems: _issuesUnder('entries[$index].counts.onlineMode'),
        changed: (String value) => _counts(
          'online count mode',
          (GlossMotdCounts next) => next.onlineMode = value,
        ),
      ),
      _number(
        context,
        id: '$id-online-value',
        label: huiText('Online count value'),
        value: counts.onlineValue,
        placeholder: '0',
        problems: _issuesUnder('entries[$index].counts.onlineValue'),
        changed: (int? value) => _counts(
          'online count value',
          (GlossMotdCounts next) => next.onlineValue = value,
        ),
      ),
      _choice(
        label: huiText('Maximum count mode'),
        value: counts.maximumMode ?? 'inherit',
        options: const <String>['inherit', 'fixed', 'offset'],
        problems: _issuesUnder('entries[$index].counts.maximumMode'),
        changed: (String value) => _counts(
          'maximum count mode',
          (GlossMotdCounts next) => next.maximumMode = value,
        ),
      ),
      _number(
        context,
        id: '$id-maximum-value',
        label: huiText('Maximum count value'),
        value: counts.maximumValue,
        placeholder: '0',
        problems: _issuesUnder('entries[$index].counts.maximumValue'),
        changed: (int? value) => _counts(
          'maximum count value',
          (GlossMotdCounts next) => next.maximumValue = value,
        ),
      ),
      SwitchField(
        id: '$id-hide-counts',
        label: huiText('Hide player counts'),
        value: counts.hide == true,
        onChanged: (bool value) =>
            _counts('hide counts', (GlossMotdCounts next) => next.hide = value),
      ),
    ]);
  }
}

/// A plain heading over a group of fields inside an entry section.
Widget _heading(String title, String tip) => dom.h3(
  classes: 'ui-section-title',
  <Widget>[TipLabel(label: Text(title), tip: tip)],
);

Widget _text(
  BuildContext context, {
  required String id,
  required String label,
  required String value,
  required void Function(String) changed,
  List<HuiIssue> problems = const <HuiIssue>[],
  String? placeholder,
  String? tip,
  bool technical = false,
}) => Field(
  label: label,
  forId: id,
  tip: tip,
  problems: problems,
  child: TextInput(
    id: id,
    value: value,
    onChanged: changed,
    placeholder: placeholder,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    styles: technical ? technicalInputStyles : null,
    attributes: technical
        ? technicalInputAttributes
        : const <String, String>{'autocomplete': 'off'},
  ),
);

/// One value per line: hostnames, states, icon paths.
Widget _lines({
  required String id,
  required String label,
  required String value,
  required void Function(String) changed,
  List<HuiIssue> problems = const <HuiIssue>[],
  String? tip,
}) => Field(
  label: label,
  forId: id,
  tip: tip,
  problems: problems,
  child: TextArea(
    id: id,
    value: value,
    rows: 3,
    onChanged: changed,
    fullWidth: true,
    styles: technicalInputStyles,
  ),
);

/// A whole number the file may leave out: an empty box removes the key.
Widget _number(
  BuildContext context, {
  required String id,
  required String label,
  required int? value,
  required void Function(int?) changed,
  List<HuiIssue> problems = const <HuiIssue>[],
  String? placeholder,
}) => Field(
  label: label,
  forId: id,
  problems: problems,
  child: TextInput(
    id: id,
    value: value?.toString() ?? '',
    placeholder: placeholder,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    attributes: const <String, String>{
      'inputmode': 'numeric',
      'autocomplete': 'off',
    },
    onChanged: (String text) {
      if (text.trim().isEmpty) {
        changed(null);
        return;
      }
      final int? number = int.tryParse(text);
      if (number != null) changed(number);
    },
  ),
);

/// Two to four wire values; an empty value reads "Automatic".
Widget _choice({
  required String label,
  required String value,
  required List<String> options,
  required void Function(String) changed,
  List<HuiIssue> problems = const <HuiIssue>[],
}) => Field(
  label: label,
  problems: problems,
  child: ToggleChoice<String>(
    label: label,
    value: value,
    options: <ChoiceOption<String>>[
      for (final String option in options)
        ChoiceOption<String>(
          option,
          option.isEmpty ? huiText('Automatic') : option,
        ),
    ],
    onChanged: changed,
  ),
);

/// Five or more wire values; an empty value reads "Automatic".
Widget _select(
  BuildContext context, {
  required String id,
  required String label,
  required String value,
  required List<String> options,
  required void Function(String) changed,
  List<HuiIssue> problems = const <HuiIssue>[],
}) => Field(
  label: label,
  forId: id,
  problems: problems,
  child: ArcaneSelect(
    id: id,
    value: value,
    size: controlSize(DensityScope.of(context)),
    fullWidth: true,
    options: <ArcaneSelectOption>[
      for (final String option in options)
        ArcaneSelectOption(
          value: option,
          label: option.isEmpty ? huiText('Automatic') : option,
        ),
    ],
    onChanged: changed,
  ),
);

/// Problems that belong to a group rather than one field.
List<Widget> _problemLines(List<HuiIssue> issues) => <Widget>[
  for (final HuiIssue issue in distinctIssues(issues)) issueNote(issue),
];

List<String>? _splitLines(String value) => value.isEmpty
    ? null
    : value.split('\n').map((String line) => line.trim()).toList();
