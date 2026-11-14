/// The chat-channel blocks a document and its variants share: hover card,
/// item and web links, mentions, message limits, the filter policy and the
/// message filters. On the document each block is a section; inside a
/// variant ([inherited]) each block is one "Override" switch followed by
/// its fields, so a variant stays one level deep. Filters and the filter
/// policy are RE2 settings: Advanced only. Every edit writes one key of
/// [value], so keys the editor does not show travel unchanged.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/validation.dart';
import '../../model/gloss_channel.dart';
import '../../model/json_codec.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/technical_input.dart';

class ChannelFieldsEditor extends StatelessWidget {
  const ChannelFieldsEditor({
    required this.value,
    required this.onChanged,
    this.fallback = const <String, Object?>{},
    this.inherited = false,
    this.mentions = true,
    this.issues = const <HuiIssue>[],
    this.path = r'$',
    this.idPrefix = 'channel',
    super.key,
  });
  final Map<String, Object?> value;
  final Map<String, Object?> fallback;
  final bool inherited;
  final bool mentions;
  final void Function(Map<String, Object?>) onChanged;

  /// The document's issues; [path] is where [value] sits in the document.
  final List<HuiIssue> issues;
  final String path;

  /// Prefixes the control ids, so a variant's fields stay unique.
  final String idPrefix;

  static final ComponentSize _size = controlSize(Density.dense);

  void _set(String key, Object? next) =>
      onChanged(<String, Object?>{...value, key: next});
  void _remove(String key) =>
      onChanged(Map<String, Object?>.of(value)..remove(key));
  Map<String, Object?> _defaults(String key) => switch (key) {
    'items' => GlossChannelItems().toJson(),
    'links' => GlossChannelLinks().toJson(),
    'mentions' => GlossChannelMentions().toJson(),
    'throttle' => GlossChannelThrottle().toJson(),
    'filtering' => GlossChannelFiltering().toJson(),
    _ => <String, Object?>{},
  };
  Map<String, Object?> _map(String key) => value[key] is Map
      ? huiReadObject(value[key], key)
      : fallback[key] is Map
      ? huiReadObject(fallback[key], key)
      : _defaults(key);

  List<HuiIssue> _issuesAt(String key) =>
      issues.where((HuiIssue issue) => issue.path == '$path.$key').toList();

  String _id(String key) => '$idPrefix-${key.replaceAll('.', '-')}';

  Widget _text(
    String key,
    String label,
    String text,
    void Function(String) change, {
    String? tip,
  }) => Field(
    label: label,
    forId: _id(key),
    tip: tip,
    problems: _issuesAt(key),
    child: TextInput(
      id: _id(key),
      value: text,
      size: _size,
      fullWidth: true,
      onChanged: change,
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );

  Widget _integer(
    String key,
    String label,
    int number,
    void Function(int) change,
  ) => Field(
    label: label,
    forId: _id(key),
    problems: _issuesAt(key),
    child: NumberField(
      id: _id(key),
      value: number.toDouble(),
      integer: true,
      step: 1,
      onChanged: (double next) => change(next.round()),
    ),
  );

  Widget _string(
    String key,
    Map<String, Object?> block,
    String field,
    String label,
  ) => _text(
    '$key.$field',
    label,
    '${block[field] ?? ''}',
    (String text) => _set(key, <String, Object?>{...block, field: text}),
  );

  Widget _enabled(String key, Map<String, Object?> block) => SwitchField(
    label: huiText('Enabled'),
    value: block['enabled'] != false,
    onChanged: (bool enabled) =>
        _set(key, <String, Object?>{...block, 'enabled': enabled}),
  );

  /// One block: a section on the document, an override switch and its
  /// fields inside a variant.
  Widget _block({
    required String key,
    required String title,
    required String overrideLabel,
    required List<Widget> Function() fields,
    List<Widget> actions = const <Widget>[],
    Object? Function()? seed,
  }) {
    if (!inherited) {
      return AppSection(
        title: title,
        memoryKey: 'channel.fields.$key',
        actions: actions,
        children: fields(),
      );
    }
    final bool present = value.containsKey(key);
    return Component.fragment(<Widget>[
      SwitchField(
        label: overrideLabel,
        value: present,
        onChanged: (bool enabled) => enabled
            ? _set(key, seed == null ? _map(key) : seed())
            : _remove(key),
      ),
      if (present) ...fields(),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final List<Object?> cards = huiReadList(value['card'] ?? fallback['card']);
    final List<Object?> filters = huiReadList(
      value['filters'] ?? fallback['filters'],
    );
    return Component.fragment(<Widget>[
      if (inherited)
        _block(
          key: 'format',
          title: huiText('Message format'),
          overrideLabel: huiText('Override format'),
          seed: () => fallback['format'] ?? '{{ message }}',
          fields: () => <Widget>[
            _text(
              'format',
              huiText('Format'),
              '${value['format']}',
              (String text) => _set('format', text),
            ),
          ],
        ),
      _block(
        key: 'card',
        title: huiText('Hover card'),
        overrideLabel: huiText('Override hover card'),
        seed: () => cards,
        fields: () => <Widget>[
          Field(
            label: huiText('Card lines'),
            forId: _id('card'),
            problems: _issuesAt('card'),
            child: TextArea(
              id: _id('card'),
              value: cards.join('\n'),
              onChanged: (String text) =>
                  _set('card', text.isEmpty ? <String>[] : text.split('\n')),
            ),
          ),
        ],
      ),
      _block(
        key: 'items',
        title: huiText('Item links'),
        overrideLabel: huiText('Override {field}', <String, Object?>{
          'field': huiText('Item links'),
        }),
        fields: () {
          final Map<String, Object?> block = _map('items');
          return <Widget>[
            _enabled('items', block),
            _string('items', block, 'token', huiText('Item token')),
            _string('items', block, 'permission', huiText('Item permission')),
            _string('items', block, 'render', huiText('Item text')),
          ];
        },
      ),
      _block(
        key: 'links',
        title: huiText('Web links'),
        overrideLabel: huiText('Override {field}', <String, Object?>{
          'field': huiText('Web links'),
        }),
        fields: () {
          final Map<String, Object?> block = _map('links');
          return <Widget>[
            _enabled('links', block),
            _string('links', block, 'render', huiText('Link text')),
          ];
        },
      ),
      if (mentions)
        _block(
          key: 'mentions',
          title: huiText('Player mentions'),
          overrideLabel: huiText('Override {field}', <String, Object?>{
            'field': huiText('Player mentions'),
          }),
          fields: () {
            final Map<String, Object?> block = _map('mentions');
            return <Widget>[
              _enabled('mentions', block),
              _string('mentions', block, 'pattern', huiText('Mention pattern')),
              _string(
                'mentions',
                block,
                'render',
                huiText('Tagged name style'),
              ),
              _string(
                'mentions',
                block,
                'messageFormat',
                huiText('Tagged message format'),
              ),
              _string('mentions', block, 'sound', huiText('Mention sound')),
              _string(
                'mentions',
                block,
                'permission',
                huiText('Sender permission'),
              ),
            ];
          },
        ),
      _block(
        key: 'throttle',
        title: huiText('Message limits'),
        overrideLabel: huiText('Override {field}', <String, Object?>{
          'field': huiText('Message limits'),
        }),
        fields: () {
          final Map<String, Object?> block = _map('throttle');
          Widget limit(String key, String label) => _integer(
            'throttle.$key',
            label,
            huiReadInt(block, key, fallback: 0),
            (int number) =>
                _set('throttle', <String, Object?>{...block, key: number}),
          );
          return <Widget>[
            limit('minIntervalTicks', huiText('Minimum interval ticks')),
            limit('repeatWindowTicks', huiText('Repeat window ticks')),
            limit('maxRepeats', huiText('Maximum repeats')),
          ];
        },
      ),
      AdvancedOnly(child: _policy()),
      AdvancedOnly(child: _filters(filters)),
    ]);
  }

  Widget _policy() => _block(
    key: 'filtering',
    title: huiText('Filter policy'),
    overrideLabel: huiText('Override {field}', <String, Object?>{
      'field': huiText('Filter policy'),
    }),
    fields: () {
      final Map<String, Object?> block = <String, Object?>{
        ...GlossChannelFiltering().toJson(),
        ..._map('filtering'),
      };
      Widget limit(String key, String label) => _integer(
        'filtering.$key',
        label,
        huiReadInt(block, key, fallback: 0),
        (int number) =>
            _set('filtering', <String, Object?>{...block, key: number}),
      );
      return <Widget>[
        Field(
          label: huiText('Pattern syntax'),
          tip: huiText(
            'RE2 expressions. Lookaround and backreferences are unsupported. '
            'Replacements are literal.',
          ),
          problems: _issuesAt('filtering.syntax'),
          child: dom.span(classes: 'hui-ltr', <Widget>[
            Text('${block['syntax']}'),
          ]),
        ),
        limit('maxInputCharacters', huiText('Maximum input characters')),
        limit('maxOutputCharacters', huiText('Maximum output characters')),
        limit('maxPatternCharacters', huiText('Maximum pattern characters')),
        limit(
          'maxReplacementCharacters',
          huiText('Maximum replacement characters'),
        ),
        limit('maxFilters', huiText('Maximum filters')),
        limit('maxMatches', huiText('Maximum matches')),
        limit('maxProgramSize', huiText('Maximum expression program size')),
        limit('maxNestingDepth', huiText('Maximum expression nesting')),
        limit('maxWorkUnits', huiText('Maximum search work units')),
        limit('budgetMicros', huiText('Elapsed target in microseconds')),
        Field(
          label: huiText('When a filter limit is reached'),
          tip: huiText(
            'Drop requires all filters to finish. Keep completed may deliver '
            'text that later filters would remove. Oversized input always '
            'drops.',
          ),
          problems: _issuesAt('filtering.onLimit'),
          child: ToggleChoice<String>(
            label: huiText('When a filter limit is reached'),
            value: '${block['onLimit']}',
            options: <ChoiceOption<String>>[
              ChoiceOption<String>('drop', huiText('Drop message')),
              ChoiceOption<String>('keep-completed', huiText('Keep completed')),
            ],
            onChanged: (String policy) => _set('filtering', <String, Object?>{
              ...block,
              'onLimit': policy,
            }),
          ),
        ),
      ];
    },
  );

  Widget _filters(List<Object?> filters) {
    void add() => _set('filters', <Object?>[
      ...filters,
      <String, Object?>{'match': 'pattern', 'replace': ''},
    ]);
    return _block(
      key: 'filters',
      title: huiText('Message filters'),
      overrideLabel: huiText('Override filters'),
      seed: () => filters,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add filter'),
          onPressed: add,
        ),
      ],
      fields: () => <Widget>[
        ReorderList(
          label: huiText('Message filters'),
          itemCount: filters.length,
          onReorder: (int from, int to) {
            final List<Object?> next = List<Object?>.of(filters);
            next.insert(to, next.removeAt(from));
            _set('filters', next);
          },
          onRemove: (int index) =>
              _set('filters', List<Object?>.of(filters)..removeAt(index)),
          itemBuilder: (int index) => Component.fragment(<Widget>[
            for (final String key in <String>['match', 'replace'])
              _text(
                'filters[$index].$key',
                key == 'match'
                    ? huiText('RE2 pattern')
                    : huiText('Literal replacement'),
                '${huiReadObject(filters[index], 'filter')[key] ?? ''}',
                (String text) {
                  final List<Object?> next = List<Object?>.of(filters);
                  next[index] = <String, Object?>{
                    ...huiReadObject(next[index], 'filter'),
                    key: text,
                  };
                  _set('filters', next);
                },
              ),
          ]),
        ),
        if (inherited)
          ActionRow(
            dense: true,
            secondary: <RowAction>[
              RowAction(huiText('Add filter'), add, icon: ArcaneIcon.plus()),
            ],
          ),
      ],
    );
  }
}
