/// Inspector body for a Gloss tab list: header and footer, list names and
/// the fixed layout, each with its per-viewer variants.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_text.dart';
import '../../logic/tablist_selection.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../gloss/gloss_text_line.dart';
import 'gloss_visibility_editor.dart';
import 'tablist_layout_editor.dart';
import '../../logic/gloss_show.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class TablistInspector extends StatefulWidget {
  const TablistInspector({required this.store, super.key});

  final EditorStore store;

  @override
  State<TablistInspector> createState() => _TablistInspectorState();
}

class _TablistInspectorState extends State<TablistInspector> {
  EditorStore get _store => component.store;

  List<HuiIssue> _issuesFor(String path) => _store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) =>
      DensityScope.dense(child: Builder(builder: _build));

  Widget _build(BuildContext context) {
    final GlossTablistDoc? doc = _store.tablistDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-tablist',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Tab list'),
          title: _store.menuId,
          tip: huiText(
            'Header/footer and list names select their own complete '
            'presentation per viewer and listed player.',
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
          sectionKey: 'tablist.visibility',
          issues: _store.issues
              .where((HuiIssue issue) => issue.path == r'$.show')
              .toList(),
          onChanged: (Object? value) => _store.mutateTablist(
            'visibility',
            (GlossTablistDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        _headerFooter(context, doc),
        _listNames(context, doc),
        TablistLayoutEditor(
          value: doc.layout,
          onChanged: (GlossTabLayout layout) => _store.mutateTablist(
            'layout',
            (GlossTablistDoc edited) => edited.layout = layout,
          ),
        ),
      ],
    );
  }

  Widget _headerFooter(BuildContext context, GlossTablistDoc doc) => AppSection(
    title: huiText('Header and footer'),
    memoryKey: 'tablist.headerFooter',
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add header/footer variant'),
        onPressed: () => _store.mutateTablist(
          'add header/footer variant',
          (GlossTablistDoc edited) => edited.headerFooter.variants.add(
            GlossTablistHeaderFooterVariant(
              id: _nextHeaderFooterId(edited),
              when: "viewer.world == 'world_nether'",
              presentation: edited.headerFooter.presentation.copy(),
            ),
          ),
        ),
      ),
    ],
    children: <Widget>[
      GlossVisibilityEditor(
        raw: doc.headerFooter.extras['show'],
        sectionKey: 'tablist.headerFooter.visibility',
        issues: _issuesFor(r'$.headerFooter.show'),
        onChanged: (Object? value) => _store.mutateTablist(
          'visibility',
          (GlossTablistDoc edited) =>
              setGlossShow(edited.headerFooter.extras, value),
        ),
      ),
      SwitchField(
        id: 'tablist-header-footer-enabled',
        label: huiText('Enabled'),
        docKey: 'tablist.headerFooter.enabled',
        value: doc.headerFooter.enabled,
        onChanged: (bool value) => _store.mutateTablist(
          'tablist header/footer enabled',
          (GlossTablistDoc edited) => edited.headerFooter.enabled = value,
        ),
      ),
      ..._headerFooterPresentation(
        context,
        'tablist-header-footer',
        doc.headerFooter.presentation,
        r'$.headerFooter.presentation',
        (void Function(GlossTablistHeaderFooterPresentation) mutate) =>
            _store.mutateTablist(
              'tablist header/footer presentation',
              (GlossTablistDoc edited) =>
                  mutate(edited.headerFooter.presentation),
            ),
      ),
      for (int index = 0; index < doc.headerFooter.variants.length; index++)
        _headerFooterVariant(context, doc, index),
    ],
  );

  Widget _headerFooterVariant(
    BuildContext context,
    GlossTablistDoc doc,
    int index,
  ) {
    final GlossTablistHeaderFooterVariant variant =
        doc.headerFooter.variants[index];
    final String path =
        r'$.headerFooter.variants['
        '$index]';
    final String id = 'tablist-header-footer-variant-$index';
    return _variant(
      id: variant.id,
      index: index,
      memoryKey: 'tablist.headerFooter.variant.$index',
      onDelete: () => _store.mutateTablist(
        'delete header/footer variant',
        (GlossTablistDoc edited) =>
            edited.headerFooter.variants.removeAt(index),
      ),
      children: <Widget>[
        ..._variantKeys(
          context,
          id: id,
          variantId: variant.id,
          priority: variant.priority,
          path: path,
          onId: (String value) => _store.mutateTablist(
            'header/footer variant id',
            (GlossTablistDoc edited) =>
                edited.headerFooter.variants[index].id = value,
          ),
          onPriority: (int value) => _store.mutateTablist(
            'header/footer variant priority',
            (GlossTablistDoc edited) =>
                edited.headerFooter.variants[index].priority = value,
          ),
        ),
        _conditionField(
          context,
          id: '$id-when',
          value: variant.when,
          path: '$path.when',
          onChanged: (String value) => _store.mutateTablist(
            'header/footer variant condition',
            (GlossTablistDoc edited) =>
                edited.headerFooter.variants[index].when = value,
          ),
        ),
        ..._headerFooterPresentation(
          context,
          id,
          variant.presentation,
          '$path.presentation',
          (void Function(GlossTablistHeaderFooterPresentation) mutate) =>
              _store.mutateTablist(
                'header/footer variant presentation',
                (GlossTablistDoc edited) =>
                    mutate(edited.headerFooter.variants[index].presentation),
              ),
        ),
      ],
    );
  }

  List<Widget> _headerFooterPresentation(
    BuildContext context,
    String id,
    GlossTablistHeaderFooterPresentation presentation,
    String path,
    void Function(void Function(GlossTablistHeaderFooterPresentation)) mutate,
  ) => <Widget>[
    _textField(
      context,
      id: '$id-header',
      label: huiText('Header'),
      value: presentation.header,
      path: '$path.header',
      docKey: 'tablist.headerFooter.presentation.header',
      onChanged: (String value) =>
          mutate((GlossTablistHeaderFooterPresentation edited) {
            edited.header = value;
          }),
    ),
    _textField(
      context,
      id: '$id-footer',
      label: huiText('Footer'),
      value: presentation.footer,
      path: '$path.footer',
      docKey: 'tablist.headerFooter.presentation.footer',
      onChanged: (String value) =>
          mutate((GlossTablistHeaderFooterPresentation edited) {
            edited.footer = value;
          }),
    ),
  ];

  Widget _listNames(BuildContext context, GlossTablistDoc doc) => AppSection(
    title: huiText('List names'),
    memoryKey: 'tablist.listNames',
    tip: huiText(
      r'Use $player and $group in formats. Highest priority wins; equal '
      'priorities use the smaller variant id.',
    ),
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add list-name variant'),
        onPressed: () => _store.mutateTablist(
          'add list-name variant',
          (GlossTablistDoc edited) => edited.listNames.variants.add(
            GlossTablistListNameVariant(
              id: _nextListNameId(edited),
              when: "inGroup('subject', 'vip')",
              presentation: edited.listNames.presentation.copy(),
            ),
          ),
        ),
      ),
    ],
    children: <Widget>[
      GlossVisibilityEditor(
        raw: doc.listNames.extras['show'],
        sectionKey: 'tablist.listNames.visibility',
        issues: _issuesFor(r'$.listNames.show'),
        onChanged: (Object? value) => _store.mutateTablist(
          'visibility',
          (GlossTablistDoc edited) =>
              setGlossShow(edited.listNames.extras, value),
        ),
      ),
      SwitchField(
        id: 'tablist-list-names-enabled',
        label: huiText('Enabled'),
        docKey: 'tablist.listNames.enabled',
        value: doc.listNames.enabled,
        onChanged: (bool value) => _store.mutateTablist(
          'tablist list names enabled',
          (GlossTablistDoc edited) => edited.listNames.enabled = value,
        ),
      ),
      _formatField(
        context,
        id: 'tablist-list-names-format',
        label: huiText('Default format'),
        presentation: doc.listNames.presentation,
        path: r'$.listNames.presentation',
        onChanged: (String value) => _store.mutateTablist(
          'tablist default list-name format',
          (GlossTablistDoc edited) =>
              edited.listNames.presentation.format = value,
        ),
      ),
      for (int index = 0; index < doc.listNames.variants.length; index++)
        _listNameVariant(context, doc, index),
    ],
  );

  Widget _listNameVariant(
    BuildContext context,
    GlossTablistDoc doc,
    int index,
  ) {
    final GlossTablistListNameVariant variant = doc.listNames.variants[index];
    final String path =
        r'$.listNames.variants['
        '$index]';
    final String id = 'tablist-list-name-variant-$index';
    return _variant(
      id: variant.id,
      index: index,
      memoryKey: 'tablist.listNames.variant.$index',
      onDelete: () => _store.mutateTablist(
        'delete list-name variant',
        (GlossTablistDoc edited) => edited.listNames.variants.removeAt(index),
      ),
      children: <Widget>[
        ..._variantKeys(
          context,
          id: id,
          variantId: variant.id,
          priority: variant.priority,
          path: path,
          onId: (String value) => _store.mutateTablist(
            'list-name variant id',
            (GlossTablistDoc edited) =>
                edited.listNames.variants[index].id = value,
          ),
          onPriority: (int value) => _store.mutateTablist(
            'list-name variant priority',
            (GlossTablistDoc edited) =>
                edited.listNames.variants[index].priority = value,
          ),
        ),
        _conditionField(
          context,
          id: '$id-when',
          value: variant.when,
          path: '$path.when',
          onChanged: (String value) => _store.mutateTablist(
            'list-name variant condition',
            (GlossTablistDoc edited) =>
                edited.listNames.variants[index].when = value,
          ),
        ),
        _formatField(
          context,
          id: '$id-format',
          label: huiText('Variant format'),
          presentation: variant.presentation,
          path: '$path.presentation',
          onChanged: (String value) => _store.mutateTablist(
            'list-name variant format',
            (GlossTablistDoc edited) =>
                edited.listNames.variants[index].presentation.format = value,
          ),
        ),
      ],
    );
  }

  /// One variant: a heading with its delete action, its fields under it.
  Widget _variant({
    required String id,
    required int index,
    required String memoryKey,
    required void Function() onDelete,
    required List<Widget> children,
  }) => AppSection(
    title: huiText('Variant {id}', <String, Object?>{
      'id': id.isEmpty ? index + 1 : id,
    }),
    memoryKey: memoryKey,
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.trash2(),
        label: huiText('Delete variant'),
        onPressed: onDelete,
      ),
    ],
    children: children,
  );

  /// The variant's id and priority: expert keys, Advanced only.
  List<Widget> _variantKeys(
    BuildContext context, {
    required String id,
    required String variantId,
    required int priority,
    required String path,
    required void Function(String) onId,
    required void Function(int) onPriority,
  }) => <Widget>[
    AdvancedOnly(
      child: Field(
        label: huiText('Id'),
        forId: '$id-id',
        problems: _issuesFor('$path.id'),
        child: TextInput(
          id: '$id-id',
          value: variantId,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          onChanged: onId,
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
      ),
    ),
    AdvancedOnly(
      child: Field(
        label: huiText('Priority'),
        forId: '$id-priority',
        problems: _issuesFor('$path.priority'),
        child: NumberField(
          id: '$id-priority',
          value: priority.toDouble(),
          integer: true,
          step: 1,
          onChanged: (double value) => onPriority(value.round()),
        ),
      ),
    ),
  ];

  Widget _conditionField(
    BuildContext context, {
    required String id,
    required String value,
    required String path,
    required void Function(String) onChanged,
  }) => Field(
    label: huiText('When'),
    forId: id,
    docKey: 'condition.when',
    problems: _issuesFor(path),
    child: TextInput(
      id: id,
      value: value,
      size: controlSize(DensityScope.of(context)),
      fullWidth: true,
      placeholder: huiText("viewer.world == 'world'"),
      onChanged: onChanged,
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );

  Widget _textField(
    BuildContext context, {
    required String id,
    required String label,
    required String value,
    required String path,
    required String docKey,
    required void Function(String) onChanged,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    problems: _issuesFor(path),
    child: dom.div(classes: 'hui-line-row', <Widget>[
      TextInput(
        id: id,
        value: value,
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        onChanged: onChanged,
        attributes: const <String, String>{
          'autocomplete': 'off',
          'spellcheck': 'false',
        },
      ),
      dom.div(classes: 'hui-line-row-preview', <Widget>[
        for (final GlossLineRender line in glossDisplayLines(
          renderGlossLine(
            value,
            animations: _store.workspaceAnimations,
            emoji: _store.workspaceEmoji,
          ),
        ))
          dom.div(<Widget>[GlossTextLine(render: line)]),
      ]),
    ]),
  );

  Widget _formatField(
    BuildContext context, {
    required String id,
    required String label,
    required GlossTablistListNamePresentation presentation,
    required String path,
    required void Function(String) onChanged,
  }) => Field(
    label: label,
    forId: id,
    docKey: 'tablist.listNames.presentation.format',
    problems: _issuesFor('$path.format'),
    child: dom.div(classes: 'hui-line-row', <Widget>[
      TextInput(
        id: id,
        value: presentation.format,
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        placeholder: huiText(r'&7$player'),
        onChanged: onChanged,
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
      ),
      dom.div(classes: 'hui-line-row-preview', <Widget>[
        GlossTextLine(
          render: renderGlossLine(
            glossTablistSubstituteTokens(
              presentation.format,
              'Cyberpwn',
              'owner',
            ),
            animations: _store.workspaceAnimations,
            emoji: _store.workspaceEmoji,
          ),
        ),
      ]),
    ]),
  );

  String _nextHeaderFooterId(GlossTablistDoc doc) => _nextId(<String>[
    for (final GlossTablistHeaderFooterVariant variant
        in doc.headerFooter.variants)
      variant.id,
  ]);

  String _nextListNameId(GlossTablistDoc doc) => _nextId(<String>[
    for (final GlossTablistListNameVariant variant in doc.listNames.variants)
      variant.id,
  ]);

  String _nextId(List<String> ids) {
    int suffix = ids.length + 1;
    String id = 'variant-$suffix';
    while (ids.contains(id)) {
      suffix++;
      id = 'variant-$suffix';
    }
    return id;
  }
}
