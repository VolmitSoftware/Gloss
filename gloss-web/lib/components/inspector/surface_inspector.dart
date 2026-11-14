/// Inspector body for a Gloss surface document: the client surface it claims,
/// the selection rule that decides who sees it, the presentation that surface
/// keeps, and the conditional variants that replace it.
///
/// Only the chosen surface's own fields are shown. `Presentation.forKind`
/// rebuilds the record out of that kind's fields and drops the rest, so an
/// editor that offered every field at once would be inviting authors to type
/// into keys the server throws away. Switching surfaces leaves the old values
/// in the file: validation names them instead of the editor deleting work.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/surface_validation.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/affixed.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_tip.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../gloss/gloss_text_line.dart';
import 'gloss_visibility_editor.dart';
import 'line_list_section.dart';
import 'surface_subscriptions_editor.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class SurfaceInspector extends StatefulWidget {
  const SurfaceInspector({required this.store, super.key});

  final EditorStore store;

  @override
  State<SurfaceInspector> createState() => _SurfaceInspectorState();
}

class _SurfaceInspectorState extends State<SurfaceInspector> {
  EditorStore get _store => component.store;

  List<HuiIssue> _issuesFor(String path) => _store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  List<HuiIssue> _issuesAt(String path) =>
      _store.issues.where((HuiIssue issue) => issue.path == path).toList();

  /// A control id from a validation path: `$.variants[0].when` becomes
  /// `surface-variants-0-when`.
  String _idFor(String path) =>
      'surface-${path.replaceAll(RegExp(r'[^A-Za-z0-9]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '')}';

  ComponentSize _size(BuildContext context) =>
      controlSize(DensityScope.of(context));

  @override
  Widget build(BuildContext context) =>
      DensityScope.dense(child: Builder(builder: _build));

  Widget _build(BuildContext context) {
    final GlossSurfaceDoc? doc = _store.surfaceDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-surface',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Surface'),
          title: _store.menuId,
          tip: huiText(
            'One line of the HUD, composed per viewer. The highest-priority '
            'matching variant supplies the whole presentation.',
          ),
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        GlossVisibilityEditor(
          raw: doc.extras['show'],
          sectionKey: 'surface.visibility',
          issues: _store.issues
              .where((HuiIssue issue) => issue.path == r'$.show')
              .toList(),
          onChanged: (Object? value) => _store.mutateSurface(
            'visibility',
            (GlossSurfaceDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        _kind(doc),
        _selection(context, doc),
        _delivery(context, doc),
        SurfaceSubscriptionsEditor(store: _store),
        AppSection(
          title: huiText('Default presentation'),
          memoryKey: 'surface.presentation',
          children: _presentation(
            context,
            doc,
            doc.presentation,
            r'$.presentation',
            (void Function(GlossSurfacePresentation) mutate) =>
                _store.mutateSurface(
                  'surface default presentation',
                  (GlossSurfaceDoc edited) => mutate(edited.presentation),
                ),
          ),
        ),
        _variants(context, doc),
      ],
    );
  }

  Widget _delivery(BuildContext context, GlossSurfaceDoc doc) {
    final Object? raw = doc.extras['delivery'];
    final Map<String, Object?> policy = raw is Map
        ? Map<String, Object?>.from(raw)
        : <String, Object?>{};
    void change(String key, Object value) =>
        _store.mutateSurface('surface delivery', (GlossSurfaceDoc edited) {
          final Object? current = edited.extras['delivery'];
          final Map<String, Object?> next = current is Map
              ? Map<String, Object?>.from(current)
              : <String, Object?>{};
          next[key] = value;
          edited.extras['delivery'] = next;
        });
    Widget choice(
      String key,
      String label,
      String fallback,
      List<String> values,
    ) => Field(
      label: label,
      problems: _issuesFor(r'$.delivery.' + key),
      child: ToggleChoice<String>(
        label: label,
        value: policy[key] is String ? policy[key]! as String : fallback,
        options: <ChoiceOption<String>>[
          for (final String value in values) ChoiceOption<String>(value, value),
        ],
        onChanged: (String value) => change(key, value),
      ),
    );
    Widget number(String key, String label, int fallback, {String? suffix}) {
      final Object? value = policy[key];
      final String id = 'surface-delivery-$key';
      return Field(
        label: label,
        forId: id,
        problems: _issuesFor(r'$.delivery.' + key),
        child: NumberField(
          id: id,
          value: (value is num ? value : fallback).toDouble(),
          integer: true,
          step: 1,
          suffix: suffix,
          onChanged: (double next) => change(key, next.round()),
        ),
      );
    }

    final List<HuiIssue> policyIssues = _issuesFor(r'$.delivery');
    return AppSection(
      title: huiText('Delivery and events'),
      memoryKey: 'surface.delivery',
      children: <Widget>[
        SwitchField(
          id: 'surface-automatic',
          label: huiText('Automatic selection'),
          value: doc.extras['automatic'] != false,
          problems: _issuesAt(r'$.automatic'),
          onChanged: (bool value) => _store.mutateSurface(
            'automatic selection',
            (GlossSurfaceDoc edited) => edited.extras['automatic'] = value,
          ),
        ),
        if (doc.resolvedSurface == glossSurfaceKindBossbar)
          Field(
            label: huiText('Bossbar group'),
            forId: 'surface-group',
            problems: _issuesAt(r'$.group'),
            child: TextInput(
              id: 'surface-group',
              value: doc.extras['group'] is String
                  ? doc.extras['group']! as String
                  : 'main',
              size: _size(context),
              fullWidth: true,
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
              onChanged: (String value) => _store.mutateSurface(
                'bossbar group',
                (GlossSurfaceDoc edited) => edited.extras['group'] = value,
              ),
            ),
          ),
        MoreFields(
          memoryKey: 'surface.more',
          open: policyIssues.isNotEmpty,
          children: <Widget>[
            choice('mode', huiText('Mode'), 'replace', <String>[
              'queue',
              'replace',
              'drop',
            ]),
            choice('preempt', huiText('Preempt'), 'always', <String>[
              'higher',
              'always',
              'never',
            ]),
            choice('overflow', huiText('Overflow'), 'reject', <String>[
              'drop-oldest',
              'reject',
            ]),
            choice('deduplicate', huiText('Deduplicate'), 'none', <String>[
              'none',
              'purpose',
              'content',
            ]),
            number('maxPending', huiText('Max pending'), 32),
            number(
              'cooldownTicks',
              huiText('Cooldown'),
              0,
              suffix: huiText('ticks'),
            ),
            number(
              'expireTicks',
              huiText('Expires after'),
              1200,
              suffix: huiText('ticks'),
            ),
            ..._problemLines(_issuesAt(r'$.delivery')),
          ],
        ),
      ],
    );
  }

  Widget _kind(GlossSurfaceDoc doc) => AppSection(
    title: huiText('Client surface'),
    memoryKey: 'surface.kind',
    tip: huiText(
      'Changing the surface leaves the other fields in the file. Gloss '
      'keeps only the ones this surface uses and drops the rest without '
      'saying so, which the Problems list calls out.',
    ),
    children: <Widget>[
      Field(
        label: huiText('Surface'),
        docKey: 'surface.surface',
        problems: _issuesFor(r'$.surface'),
        child: ToggleChoice<String>(
          label: huiText('Surface'),
          value: doc.resolvedSurface,
          options: <ChoiceOption<String>>[
            ChoiceOption<String>(
              glossSurfaceKindActionbar,
              huiText('Action bar'),
            ),
            ChoiceOption<String>(glossSurfaceKindBossbar, huiText('Boss bar')),
            ChoiceOption<String>(glossSurfaceKindTitle, huiText('Title card')),
          ],
          onChanged: (String value) => _store.mutateSurface(
            'surface kind',
            (GlossSurfaceDoc edited) => edited.surface = value,
          ),
        ),
      ),
    ],
  );

  Widget _selection(BuildContext context, GlossSurfaceDoc doc) => AppSection(
    title: huiText('Surface selection'),
    memoryKey: 'surface.selection',
    tip: huiText(
      'A document with no condition takes false, so it never reaches '
      'anyone until you write one. Highest priority wins its lane.',
    ),
    children: <Widget>[
      AdvancedOnly(
        child: _integerField(
          label: huiText('Priority'),
          docKey: 'surface.select.priority',
          value: doc.select.priority,
          path: r'$.select.priority',
          onChanged: (int value) => _store.mutateSurface(
            'surface select priority',
            (GlossSurfaceDoc edited) => edited.select.priority = value,
          ),
        ),
      ),
      _conditionField(
        context,
        label: huiText('When'),
        value: doc.select.when,
        path: r'$.select.when',
        onChanged: (String value) => _store.mutateSurface(
          'surface select condition',
          (GlossSurfaceDoc edited) => edited.select.when = value,
        ),
      ),
    ],
  );

  Widget _variants(BuildContext context, GlossSurfaceDoc doc) => AppSection(
    title: huiText('Conditional variants'),
    memoryKey: 'surface.variants',
    count: doc.variants.length,
    tip: huiText(
      'Variants are complete presentations. Matching variants never '
      'merge; the highest priority wins and the default is the fallback.',
    ),
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add variant'),
        onPressed: () => _store.mutateSurface(
          'add surface variant',
          (GlossSurfaceDoc edited) => edited.variants.add(
            GlossSurfaceVariant(
              id: _nextVariantId(edited),
              priority: 10,
              when: 'viewer.healthPercent <= 25',
              presentation: edited.presentation.copy(),
            ),
          ),
        ),
      ),
    ],
    children: <Widget>[
      if (doc.variants.isEmpty)
        ArcaneFieldDescription(
          huiText('No variants. The default presentation applies.'),
        ),
      for (int index = 0; index < doc.variants.length; index++)
        _variant(context, doc, index),
    ],
  );

  Widget _variant(BuildContext context, GlossSurfaceDoc doc, int index) {
    final GlossSurfaceVariant variant = doc.variants[index];
    final String path =
        r'$.variants['
        '$index]';
    final String idField = _idFor('$path.id');
    return AppSection(
      title: huiText('Variant {id}', <String, Object?>{
        'id': variant.id.isEmpty ? index + 1 : variant.id,
      }),
      memoryKey: 'surface.variant.$index',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete variant'),
          onPressed: () => _store.mutateSurface(
            'delete surface variant',
            (GlossSurfaceDoc edited) => edited.variants.removeAt(index),
          ),
        ),
      ],
      children: <Widget>[
        AdvancedOnly(
          child: Field(
            label: huiText('Id'),
            forId: idField,
            problems: _issuesFor('$path.id'),
            child: TextInput(
              id: idField,
              value: variant.id,
              size: _size(context),
              fullWidth: true,
              onChanged: (String value) => _store.mutateSurface(
                'surface variant id',
                (GlossSurfaceDoc edited) => edited.variants[index].id = value,
              ),
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
            ),
          ),
        ),
        AdvancedOnly(
          child: _integerField(
            label: huiText('Priority'),
            value: variant.priority,
            path: '$path.priority',
            onChanged: (int value) => _store.mutateSurface(
              'surface variant priority',
              (GlossSurfaceDoc edited) =>
                  edited.variants[index].priority = value,
            ),
          ),
        ),
        _conditionField(
          context,
          label: huiText('When'),
          value: variant.when,
          path: '$path.when',
          onChanged: (String value) => _store.mutateSurface(
            'surface variant condition',
            (GlossSurfaceDoc edited) => edited.variants[index].when = value,
          ),
        ),
        ..._presentation(
          context,
          doc,
          variant.presentation,
          '$path.presentation',
          (void Function(GlossSurfacePresentation) mutate) =>
              _store.mutateSurface(
                'surface variant presentation',
                (GlossSurfaceDoc edited) =>
                    mutate(edited.variants[index].presentation),
              ),
        ),
      ],
    );
  }

  /// The fields of one presentation, flat, so the default section and each
  /// variant section hold them without another level.
  List<Widget> _presentation(
    BuildContext context,
    GlossSurfaceDoc doc,
    GlossSurfacePresentation presentation,
    String path,
    void Function(void Function(GlossSurfacePresentation)) mutate,
  ) {
    final String surface = doc.resolvedSurface;
    return <Widget>[
      if (surface == glossSurfaceKindActionbar)
        _lineField(
          context,
          label: huiText('Text'),
          docKey: 'surface.presentation.text',
          value: presentation.text ?? '',
          path: '$path.text',
          placeholder: huiText(r'&7Welcome, &f{{ player.name }}'),
          onChanged: (String value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.text = value;
              }),
        ),
      if (surface != glossSurfaceKindActionbar)
        _lineField(
          context,
          label: huiText('Title'),
          docKey: 'surface.presentation.title',
          value: presentation.title ?? '',
          path: '$path.title',
          placeholder: huiText('&d&lWELCOME BACK'),
          onChanged: (String value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.title = value;
              }),
        ),
      if (surface == glossSurfaceKindTitle)
        _lineField(
          context,
          label: huiText('Subtitle'),
          docKey: 'surface.presentation.subtitle',
          value: presentation.subtitle ?? '',
          path: '$path.subtitle',
          placeholder: huiText('&7{{ viewer.name }}'),
          onChanged: (String value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.subtitle = value;
              }),
        ),
      if (surface == glossSurfaceKindBossbar) ...<Widget>[
        for (final (String flag, String label) in <(String, String)>[
          ('darken_sky', huiText('Darken sky')),
          ('play_boss_music', huiText('Boss music')),
          ('create_fog', huiText('Create fog')),
        ])
          SwitchField(
            id: '${_idFor(path)}-$flag',
            label: label,
            value: presentation.flags?.contains(flag) ?? false,
            onChanged: (bool value) =>
                mutate((GlossSurfacePresentation edited) {
                  final Set<String> flags = <String>{...?edited.flags};
                  if (value) {
                    flags.add(flag);
                  } else {
                    flags.remove(flag);
                  }
                  edited.flags = flags.toList();
                }),
          ),
        _lineField(
          context,
          label: huiText('Progress'),
          docKey: 'surface.presentation.progress',
          value: presentation.progress ?? '',
          path: '$path.progress',
          placeholder: huiText('{{ viewer.health / viewer.maxHealth }}'),
          preview: false,
          onChanged: (String value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.progress = value.isEmpty ? null : value;
              }),
        ),
        _nameField(
          context,
          label: huiText('Colour'),
          docKey: 'surface.presentation.color',
          value: presentation.color,
          allowed: glossSurfaceColors,
          fallback: glossSurfaceDefaultColor,
          path: '$path.color',
          onChanged: (String? value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.color = value;
              }),
        ),
        _nameField(
          context,
          label: huiText('Notch style'),
          docKey: 'surface.presentation.style',
          value: presentation.style,
          allowed: glossSurfaceStyles,
          fallback: glossSurfaceDefaultStyle,
          path: '$path.style',
          onChanged: (String? value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.style = value;
              }),
        ),
      ],
      if (surface == glossSurfaceKindTitle) ...<Widget>[
        _optionalIntField(
          context,
          label: huiText('Fade in'),
          docKey: 'surface.presentation.fadeInTicks',
          value: presentation.fadeInTicks,
          fallback: glossSurfaceDefaultFadeInTicks,
          path: '$path.fadeInTicks',
          onChanged: (int? value) => mutate((GlossSurfacePresentation edited) {
            edited.fadeInTicks = value;
          }),
        ),
        _optionalIntField(
          context,
          label: huiText('Stay'),
          docKey: 'surface.presentation.stayTicks',
          value: presentation.stayTicks,
          fallback: glossSurfaceDefaultStayTicks,
          path: '$path.stayTicks',
          onChanged: (int? value) => mutate((GlossSurfacePresentation edited) {
            edited.stayTicks = value;
          }),
        ),
        _optionalIntField(
          context,
          label: huiText('Fade out'),
          docKey: 'surface.presentation.fadeOutTicks',
          value: presentation.fadeOutTicks,
          fallback: glossSurfaceDefaultFadeOutTicks,
          path: '$path.fadeOutTicks',
          onChanged: (int? value) => mutate((GlossSurfacePresentation edited) {
            edited.fadeOutTicks = value;
          }),
        ),
        _nameField(
          context,
          label: huiText('Trigger'),
          docKey: 'surface.presentation.trigger',
          value: presentation.trigger,
          allowed: glossSurfaceTriggers,
          fallback: glossSurfaceDefaultTrigger,
          path: '$path.trigger',
          onChanged: (String? value) =>
              mutate((GlossSurfacePresentation edited) {
                edited.trigger = value;
              }),
        ),
        _optionalIntField(
          context,
          label: huiText('Repeat every'),
          docKey: 'surface.presentation.repeatTicks',
          value: presentation.repeatTicks,
          fallback: null,
          path: '$path.repeatTicks',
          onChanged: (int? value) => mutate((GlossSurfacePresentation edited) {
            edited.repeatTicks = value;
          }),
        ),
      ],
      _slots(presentation, path, mutate),
      _nameField(
        context,
        label: huiText('Compositor lane'),
        docKey: 'surface.presentation.priority',
        value: presentation.priority,
        allowed: glossSurfacePriorities,
        fallback: glossSurfaceDefaultPriority,
        path: '$path.priority',
        onChanged: (String? value) => mutate((GlossSurfacePresentation edited) {
          edited.priority = value;
        }),
      ),
      _optionalIntField(
        context,
        label: huiText('Lifetime'),
        docKey: 'surface.presentation.ttlTicks',
        value: presentation.ttlTicks,
        fallback: null,
        path: '$path.ttlTicks',
        onChanged: (int? value) => mutate((GlossSurfacePresentation edited) {
          edited.ttlTicks = value;
        }),
      ),
    ];
  }

  Widget _slots(
    GlossSurfacePresentation presentation,
    String path,
    void Function(void Function(GlossSurfacePresentation)) mutate,
  ) {
    final List<String> selected = presentation.slots ?? const <String>[];
    return Field(
      label: huiText('Slots'),
      docKey: 'surface.presentation.slots',
      problems: _issuesFor('$path.slots'),
      child: ToggleChips<String>(
        label: huiText('Slots'),
        values: selected.toSet(),
        options: <ChoiceOption<String>>[
          for (final String slot in glossSurfaceSlots)
            ChoiceOption<String>(slot, slot),
        ],
        onChanged: (Set<String> picked) =>
            mutate((GlossSurfacePresentation edited) {
              final List<String> next = <String>[
                for (final String candidate in glossSurfaceSlots)
                  if (picked.contains(candidate)) candidate,
              ];
              edited.slots = next.isEmpty ? null : next;
            }),
      ),
    );
  }

  Widget _lineField(
    BuildContext context, {
    required String label,
    required String docKey,
    required String value,
    required String path,
    required String placeholder,
    required void Function(String) onChanged,
    bool preview = true,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: Component.fragment(<Widget>[
        TextInput(
          id: id,
          value: value,
          size: _size(context),
          fullWidth: true,
          placeholder: placeholder,
          onChanged: onChanged,
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
        if (preview && value.isNotEmpty)
          dom.div(classes: 'hui-line-row-preview', <Widget>[
            GlossTextLine(
              render: renderGlossLine(
                value,
                animations: _store.workspaceAnimations,
                emoji: _store.workspaceEmoji,
                expressionSamples: glossSurfaceSamples,
              ),
            ),
            ...huiMissingAnimationChips(
              value,
              _store.workspaceAnimations,
              where: 'on the HUD',
            ),
          ]),
      ]),
    );
  }

  /// A field whose value is one of a fixed set of wire names, plus the
  /// "leave it out" option that takes the runtime default.
  Widget _nameField(
    BuildContext context, {
    required String label,
    required String docKey,
    required String? value,
    required List<String> allowed,
    required String fallback,
    required String path,
    required void Function(String?) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: ArcaneSelect(
        id: id,
        value: value ?? '',
        size: _size(context),
        fullWidth: true,
        options: <ArcaneSelectOption>[
          ArcaneSelectOption(
            value: '',
            label: huiText('Default ({value})', <String, Object?>{
              'value': fallback,
            }),
          ),
          for (final String option in allowed)
            ArcaneSelectOption(value: option, label: option),
        ],
        onChanged: (String next) => onChanged(next.isEmpty ? null : next),
      ),
    );
  }

  /// A tick count the file may leave out. Clearing the box (or the reset
  /// button) removes the key rather than writing a zero the server would
  /// clamp.
  Widget _optionalIntField(
    BuildContext context, {
    required String label,
    required String docKey,
    required int? value,
    required int? fallback,
    required String path,
    required void Function(int?) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      tip: fallback == null
          ? tipWithDoc(huiText('Leave empty to leave the key out.'), docKey)
          : null,
      problems: _issuesFor(path),
      defaultValue: fallback?.toString(),
      onReset: fallback == null || value == null ? null : () => onChanged(null),
      child: Affixed(
        suffix: huiText('ticks'),
        child: TextInput(
          id: id,
          value: value == null ? '' : '$value',
          size: _size(context),
          fullWidth: true,
          placeholder: fallback?.toString(),
          onChanged: (String raw) {
            if (raw.trim().isEmpty) {
              onChanged(null);
              return;
            }
            final int? parsed = int.tryParse(raw);
            if (parsed != null) onChanged(parsed);
          },
          styles: technicalInputStyles,
          attributes: <String, String>{
            ...technicalInputAttributes,
            'inputmode': 'numeric',
          },
        ),
      ),
    );
  }

  Widget _integerField({
    required String label,
    required int value,
    required String path,
    required void Function(int) onChanged,
    String? docKey,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: NumberField(
        id: id,
        value: value.toDouble(),
        integer: true,
        step: 1,
        onChanged: (double next) => onChanged(next.round()),
      ),
    );
  }

  Widget _conditionField(
    BuildContext context, {
    required String label,
    required String value,
    required String path,
    required void Function(String) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: 'condition.when',
      problems: _issuesFor(path),
      child: TextInput(
        id: id,
        value: value,
        size: _size(context),
        fullWidth: true,
        placeholder: huiText("viewer.world == 'world'"),
        onChanged: onChanged,
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
      ),
    );
  }

  String _nextVariantId(GlossSurfaceDoc doc) {
    int suffix = doc.variants.length + 1;
    String id = 'variant-$suffix';
    while (doc.variants.any((GlossSurfaceVariant item) => item.id == id)) {
      suffix++;
      id = 'variant-$suffix';
    }
    return id;
  }
}

/// Problems that belong to a group rather than one field.
List<Widget> _problemLines(List<HuiIssue> issues) => <Widget>[
  for (final HuiIssue issue in distinctIssues(issues)) issueNote(issue),
];
