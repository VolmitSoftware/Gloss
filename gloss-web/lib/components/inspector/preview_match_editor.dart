/// Document-level inspector for a container-preview document: shown whenever
/// no element is selected, the way [MenuInspector] shows the menu root.
///
/// `match`/`variants` stay simple: blocks and entities are comma lists (a
/// glob is a name with a `*` in it), `special` is a small fixed choice, and
/// `vars` are key/value rows with a colour swatch for anything starting with
/// `#`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/links.dart';
import '../../logic/preview_field_text.dart';
import '../../logic/validation.dart';
import '../../model/hui_icons.dart';
import '../../model/particle_layer.dart';
import '../../model/preview_doc.dart';
import '../../state/editor_store.dart';
import '../../ui/color_field.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_tip.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import 'display_style_editor.dart';
import 'extras_editor.dart';
import 'particle_layers_editor.dart';
import 'preview_color_swatch.dart';
import 'preview_expr_field.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

const List<String> _specialValues = <String>[
  'enderChest',
  'locked',
  'anyInventoryHolder',
];

String _specialValueLabel(String value) => switch (value) {
  'enderChest' => huiText('Ender chest'),
  'locked' => huiText('Locked'),
  'anyInventoryHolder' => huiText('Any inventory holder'),
  _ => value,
};

final RegExp _identifierPattern = RegExp(r'^[a-zA-Z_][a-zA-Z0-9_]*$');

/// Whether [issue] sits at [path] or under it (`path.key`, `path[0]`).
bool _under(HuiIssue issue, String path) =>
    issue.path == path ||
    issue.path.startsWith('$path.') ||
    issue.path.startsWith('$path[');

bool _sameNames(List<String> a, List<String> b) {
  if (a.length != b.length) return false;
  for (int index = 0; index < a.length; index++) {
    if (a[index] != b[index]) return false;
  }
  return true;
}

class PreviewMatchEditor extends StatelessWidget {
  const PreviewMatchEditor({required this.store, super.key});

  final EditorStore store;

  HuiPreviewDoc get _doc => store.previewDoc!;

  /// Document-level issues at [path] or under it.
  List<HuiIssue> _issuesFor(String path) =>
      store.issues.where((HuiIssue issue) => _under(issue, path)).toList();

  List<HuiIssue> _issuesAt(String path) =>
      store.issues.where((HuiIssue issue) => issue.path == path).toList();

  void _mutate(String label, void Function(HuiPreviewDoc doc) fn) =>
      store.mutatePreview(label, fn);

  void _undoToast(String name) => toastInfo(
    huiText('Deleted {id}.', <String, Object?>{'id': name}),
    action: ToastAction(
      label: huiText('Undo'),
      onPressed: () {
        store.performUndo();
      },
    ),
  );

  @override
  Widget build(BuildContext context) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    return DenseBox(
      classes: 'hui-inspector-body is-container-preview',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Container preview'),
          title: store.menuId,
          tip: huiText(
            'Nothing is selected, so these are the document-wide '
            'settings: which blocks and entities draw this card, its '
            'variants, and the chrome around it. Pick an element in the '
            'list to edit it.',
          ),
        ),
        _matchSection(size),
        _varsSection(),
        _variantsSection(size),
        _cardSection(),
        DisplayStyleEditor(
          title: 'Text style',
          sectionKey: 'preview.textStyle',
          style: _doc.textStyle,
          issues: _issuesFor('textStyle'),
          onChanged: (String label, HuiIconStyle? style) =>
              _mutate(label, (HuiPreviewDoc doc) => doc.textStyle = style),
        ),
        DisplayStyleEditor(
          title: 'Item style',
          sectionKey: 'preview.itemStyle',
          style: _doc.itemStyle,
          defaults: HuiIconStyle(blockLight: 15, skyLight: 15),
          issues: _issuesFor('itemStyle'),
          onChanged: (String label, HuiIconStyle? style) =>
              _mutate(label, (HuiPreviewDoc doc) => doc.itemStyle = style),
        ),
        ParticleLayersEditor(
          layers: _doc.particleLayers,
          sectionKey: 'preview.particleLayers',
          mutate: (String label, void Function(List<GlossParticleLayer>) edit) =>
              _mutate(label, (HuiPreviewDoc doc) => edit(doc.particleLayers)),
        ),
        _settingsSection(),
        _install(),
        AdvancedOnly(child: _extras()),
      ],
    );
  }

  // --- match ----------------------------------------------------------------

  Widget _matchSection(ComponentSize size) => AppSection(
    title: huiText('Match'),
    memoryKey: 'preview.match',
    docKey: 'preview.match',
    children: <Widget>[
      _NameListField(
        id: 'hui-preview-match-blocks',
        label: huiText('Blocks'),
        docKey: 'preview.match.blocks',
        placeholder: huiText('CHEST or OAK_*'),
        values: _doc.match.blocks,
        // The "matches nothing at all" note sits here: a block is its fix.
        problems: <HuiIssue>[
          ..._issuesFor('match.blocks'),
          ..._issuesAt('match'),
        ],
        onChanged: (List<String> next) =>
            _mutate('match blocks', (HuiPreviewDoc d) => d.match.blocks = next),
      ),
      _NameListField(
        id: 'hui-preview-match-entities',
        label: huiText('Entities'),
        docKey: 'preview.match.entities',
        values: _doc.match.entities,
        problems: _issuesFor('match.entities'),
        onChanged: (List<String> next) => _mutate(
          'match entities',
          (HuiPreviewDoc d) => d.match.entities = next,
        ),
      ),
      _specialField(
        id: 'hui-preview-match-special',
        value: _doc.match.special,
        docKey: 'preview.match.special',
        problems: _issuesFor('match.special'),
        size: size,
        onChanged: (String? value) =>
            _mutate('match special', (HuiPreviewDoc d) => d.match.special = value),
      ),
      Field(
        label: huiText('Priority'),
        forId: 'hui-preview-match-priority',
        docKey: 'preview.match.priority',
        defaultValue: '0',
        onReset: (_doc.match.priority ?? 0) == 0
            ? null
            : () => _mutate(
                'match priority',
                (HuiPreviewDoc d) => d.match.priority = 0,
              ),
        problems: _issuesFor('match.priority'),
        child: NumberField(
          id: 'hui-preview-match-priority',
          value: (_doc.match.priority ?? 0).toDouble(),
          integer: true,
          min: -1000000,
          max: 1000000,
          onChanged: (double value) => _mutate(
            'match priority',
            (HuiPreviewDoc d) => d.match.priority = value.round(),
          ),
        ),
      ),
      AdvancedOnly(
        child: PreviewExprField(
          label: huiText('Visibility expression'),
          raw: _doc.show,
          showCondition: true,
          kind: PreviewExprKind.boolean,
          placeholder: huiText('true'),
          docKey: 'preview.show',
          issues: _issuesFor('show'),
          onChanged: (Object? value) =>
              _mutate('show', (HuiPreviewDoc doc) => doc.show = value),
        ),
      ),
      AdvancedOnly(
        child: ExtrasEditor(
          title: huiText('Match'),
          extras: _doc.match.extras,
          onChanged: (String label, Map<String, dynamic> next) =>
              _mutate(label, (HuiPreviewDoc d) => d.match.extras = next),
        ),
      ),
    ],
  );

  Widget _specialField({
    required String id,
    required String? value,
    required List<HuiIssue> problems,
    required ComponentSize size,
    required void Function(String? value) onChanged,
    String? docKey,
    String? tip,
  }) => Field(
    label: huiText('Special'),
    forId: id,
    docKey: docKey,
    tip: tip,
    problems: problems,
    child: ArcaneSelect(
      id: id,
      value: value ?? 'none',
      size: size,
      fullWidth: true,
      options: <ArcaneSelectOption>[
        ArcaneSelectOption(label: huiText('None'), value: 'none'),
        for (final String special in _specialValues)
          ArcaneSelectOption(
            label: _specialValueLabel(special),
            value: special,
          ),
      ],
      onChanged: (String picked) => onChanged(picked == 'none' ? null : picked),
    ),
  );

  // --- vars -----------------------------------------------------------------

  Widget _varsSection() => AppSection(
    title: huiText('Variables'),
    memoryKey: 'preview.vars',
    docKey: 'preview.vars',
    count: _doc.match.vars.length,
    children: <Widget>[
      _PreviewVarsRows(
        idPrefix: 'hui-preview-var',
        pathPrefix: 'match.vars',
        vars: _doc.match.vars,
        issues: _issuesFor('match.vars'),
        onChanged: (Map<String, dynamic> next) =>
            _mutate('match vars', (HuiPreviewDoc d) => d.match.vars = next),
      ),
    ],
  );

  // --- variants -------------------------------------------------------------

  Widget _variantsSection(ComponentSize size) {
    final int count = _doc.variants.length;
    return AppSection(
      title: huiText('Variants'),
      memoryKey: 'preview.variants',
      docKey: 'preview.variants',
      count: count,
      description: count == 0
          ? huiText("No variants. Every target uses the document's own vars.")
          : null,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add variant'),
          tipAtEnd: true,
          onPressed: () => _mutate(
            'add variant',
            (HuiPreviewDoc d) => d.variants.add(HuiPreviewVariant()),
          ),
        ),
      ],
      children: <Widget>[
        for (int index = 0; index < count; index++) _variant(index, size),
      ],
    );
  }

  void _moveVariant(int from, int to) =>
      _mutate('move variant', (HuiPreviewDoc d) {
        final HuiPreviewVariant moved = d.variants.removeAt(from);
        d.variants.insert(to, moved);
      });

  /// One variant under its own heading. Only the last one starts open, so a
  /// new variant opens and the one before it closes.
  Widget _variant(int index, ComponentSize size) {
    final HuiPreviewVariant variant = _doc.variants[index];
    final String path = 'variants[$index]';
    final String name = huiText('Variant {value}', <String, Object?>{
      'value': index + 1,
    });
    final int count = _doc.variants.length;
    return AppSection(
      title: name,
      open: index == count - 1,
      actions: <Widget>[
        AppMenu(
          icon: ArcaneIcon.ellipsis(),
          iconLabel: huiText('Actions for {label}', <String, Object?>{
            'label': name,
          }),
          alignEnd: true,
          entries: <AppMenuEntry>[
            AppMenuEntry(
              huiText('Move up'),
              index == 0 ? null : () => _moveVariant(index, index - 1),
              icon: ArcaneIcon.arrowUp(),
            ),
            AppMenuEntry(
              huiText('Move down'),
              index >= count - 1 ? null : () => _moveVariant(index, index + 1),
              icon: ArcaneIcon.arrowDown(),
            ),
            const AppMenuEntry.separator(),
            AppMenuEntry(
              huiText('Delete variant {number}', <String, Object?>{
                'number': index + 1,
              }),
              () {
                _mutate(
                  'remove variant',
                  (HuiPreviewDoc d) => d.variants.removeAt(index),
                );
                _undoToast(name);
              },
              icon: ArcaneIcon.trash2(),
              destructive: true,
            ),
          ],
        ),
      ],
      children: <Widget>[
        _NameListField(
          id: 'hui-preview-$path-blocks',
          label: huiText('Blocks'),
          docKey: 'preview.variant.blocks',
          values: variant.blocks,
          problems: _issuesFor('$path.blocks'),
          onChanged: (List<String> next) => _mutate(
            'variant blocks',
            (HuiPreviewDoc d) => d.variants[index].blocks = next,
          ),
        ),
        _NameListField(
          id: 'hui-preview-$path-entities',
          label: huiText('Entities'),
          docKey: 'preview.variant.entities',
          values: variant.entities,
          problems: _issuesFor('$path.entities'),
          onChanged: (List<String> next) => _mutate(
            'variant entities',
            (HuiPreviewDoc d) => d.variants[index].entities = next,
          ),
        ),
        _specialField(
          id: 'hui-preview-$path-special',
          value: variant.special,
          tip: huiText(
            'Optionally limits this variant to an ender chest, locked target or '
            'inventory-holder fallback.',
          ),
          problems: _issuesFor('$path.special'),
          size: size,
          onChanged: (String? value) => _mutate(
            'variant special',
            (HuiPreviewDoc d) => d.variants[index].special = value,
          ),
        ),
        AdvancedOnly(
          child: Field(
            label: huiText('Priority'),
            forId: 'hui-preview-$path-priority',
            tip: huiText(
              'Overrides the document priority when this variant matches.',
            ),
            problems: _issuesFor('$path.priority'),
            child: NumberField(
              id: 'hui-preview-$path-priority',
              value: (variant.priority ?? 0).toDouble(),
              integer: true,
              min: -1000000,
              max: 1000000,
              onChanged: (double value) => _mutate(
                'variant priority',
                (HuiPreviewDoc d) => d.variants[index].priority = value.round(),
              ),
            ),
          ),
        ),
        _PreviewVarsRows(
          idPrefix: 'hui-preview-$path-var',
          pathPrefix: '$path.vars',
          docKey: 'preview.variant.vars',
          vars: variant.vars,
          issues: _issuesFor('$path.vars'),
          onChanged: (Map<String, dynamic> next) => _mutate(
            'variant vars',
            (HuiPreviewDoc d) => d.variants[index].vars = next,
          ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Variant'),
            extras: variant.extras,
            onChanged: (String label, Map<String, dynamic> next) => _mutate(
              label,
              (HuiPreviewDoc d) => d.variants[index].extras = next,
            ),
          ),
        ),
      ],
    );
  }

  // --- card -----------------------------------------------------------------

  Widget _cardSection() {
    final HuiPreviewCard? card = _doc.card;
    return AppSection(
      title: huiText('Card'),
      memoryKey: 'preview.card',
      docKey: 'preview.card',
      children: <Widget>[
        SwitchField(
          label: huiText('Draw card chrome'),
          value: card != null,
          onChanged: (bool value) => _mutate(
            value ? 'add card' : 'remove card',
            (HuiPreviewDoc d) =>
                d.card = value ? HuiPreviewCard(framed: true) : null,
          ),
        ),
        if (card != null) ..._cardFields(card),
      ],
    );
  }

  List<Widget> _cardFields(HuiPreviewCard card) => <Widget>[
    AdvancedOnly(
      child: PreviewExprField(
        label: huiText('Visibility expression'),
        raw: card.show,
        showCondition: true,
        kind: PreviewExprKind.boolean,
        placeholder: huiText('true'),
        docKey: 'preview.card.show',
        issues: _issuesFor('card.show'),
        onChanged: (Object? value) =>
            _mutate('card show', (HuiPreviewDoc doc) => doc.card?.show = value),
      ),
    ),
    PreviewExprField(
      label: huiText('Framed'),
      raw: card.framed,
      kind: PreviewExprKind.boolean,
      placeholder: huiText('true'),
      docKey: 'preview.card.framed',
      issues: _issuesFor('card.framed'),
      onChanged: (Object? v) =>
          _mutate('card framed', (HuiPreviewDoc d) => d.card?.framed = v),
    ),
    PreviewExprField(
      label: huiText('Title'),
      raw: card.title,
      kind: PreviewExprKind.string,
      placeholder: huiText("'Furnace'"),
      docKey: 'preview.card.title',
      issues: _issuesFor('card.title'),
      onChanged: (Object? v) => _mutate(
        'card title',
        (HuiPreviewDoc d) => d.card?.title = v as String?,
      ),
    ),
    PreviewExprField(
      label: huiText('Accent'),
      raw: card.accent,
      kind: PreviewExprKind.string,
      placeholder: huiText('#FF808080'),
      docKey: 'preview.card.accent',
      trailing: previewColorPicker(
        card.accent,
        label: huiText('card accent'),
        onPicked: (String hex) =>
            _mutate('card accent', (HuiPreviewDoc d) => d.card?.accent = hex),
      ),
      issues: _issuesFor('card.accent'),
      onChanged: (Object? v) => _mutate(
        'card accent',
        (HuiPreviewDoc d) => d.card?.accent = v as String?,
      ),
    ),
    _cardColor(
      key: 'backgroundArgb',
      label: huiText('Background'),
      value: card.backgroundArgb,
      fallback: '#F21B1B22',
      hasDefault: true,
      write: (HuiPreviewCard c, String? v) => c.backgroundArgb = v,
    ),
    _cardColor(
      key: 'trayArgb',
      label: huiText('Tray color'),
      value: card.trayArgb,
      fallback: '#FF33333E',
      hasDefault: true,
      write: (HuiPreviewCard c, String? v) => c.trayArgb = v,
    ),
    // Null border and title colours follow the accent, so there is no one
    // default value to show or reset to.
    _cardColor(
      key: 'borderArgb',
      label: huiText('Border color'),
      value: card.borderArgb,
      fallback: '#CCCBD0D9',
      write: (HuiPreviewCard c, String? v) => c.borderArgb = v,
    ),
    _cardColor(
      key: 'titleArgb',
      label: huiText('Title background'),
      value: card.titleArgb,
      fallback: '#E6CBD0D9',
      write: (HuiPreviewCard c, String? v) => c.titleArgb = v,
    ),
    MoreFields(
      memoryKey: 'preview.card.more',
      children: <Widget>[
        _cardPixels(
          key: 'minHalfWidth',
          label: huiText('Min half width'),
          value: card.minHalfWidth,
          fallback: 82,
          max: 2000,
          write: (HuiPreviewCard c, int? v) => c.minHalfWidth = v,
        ),
        _cardPixels(
          key: 'padding',
          label: huiText('Padding'),
          value: card.padding,
          fallback: 7,
          write: (HuiPreviewCard c, int? v) => c.padding = v,
        ),
        _cardPixels(
          key: 'borderWidth',
          label: huiText('Border width'),
          value: card.borderWidth,
          fallback: 3,
          write: (HuiPreviewCard c, int? v) => c.borderWidth = v,
        ),
        _cardPixels(
          key: 'trayPadding',
          label: huiText('Tray padding'),
          value: card.trayPadding,
          fallback: 4,
          write: (HuiPreviewCard c, int? v) => c.trayPadding = v,
        ),
        _cardPixels(
          key: 'titleHeight',
          label: huiText('Title height'),
          value: card.titleHeight,
          fallback: 17,
          write: (HuiPreviewCard c, int? v) => c.titleHeight = v,
        ),
        _cardPixels(
          key: 'titleGap',
          label: huiText('Title gap'),
          value: card.titleGap,
          fallback: 6,
          write: (HuiPreviewCard c, int? v) => c.titleGap = v,
        ),
      ],
    ),
    AdvancedOnly(
      child: ExtrasEditor(
        title: huiText('Card'),
        extras: card.extras,
        onChanged: (String label, Map<String, dynamic> next) =>
            _mutate(label, (HuiPreviewDoc d) => d.card?.extras = next),
      ),
    ),
  ];

  /// A card length in font pixels; null keeps Gloss's own default.
  Widget _cardPixels({
    required String key,
    required String label,
    required int? value,
    required int fallback,
    required void Function(HuiPreviewCard card, int? value) write,
    double max = 256,
  }) {
    final String id = 'hui-preview-card-$key';
    void set(int? next) => _mutate('card $key', (HuiPreviewDoc d) {
      final HuiPreviewCard? card = d.card;
      if (card != null) write(card, next);
    });
    return Field(
      label: label,
      forId: id,
      docKey: 'preview.card.$key',
      defaultValue: '$fallback',
      onReset: value == null ? null : () => set(null),
      problems: _issuesFor('card.$key'),
      child: NumberField(
        id: id,
        value: (value ?? fallback).toDouble(),
        min: 0,
        max: max,
        integer: true,
        onChanged: (double next) => set(next.round()),
      ),
    );
  }

  /// A full `#AARRGGBB` card colour. [hasDefault] shows [fallback] as the
  /// value Gloss uses when the key is omitted.
  Widget _cardColor({
    required String key,
    required String label,
    required String? value,
    required String fallback,
    required void Function(HuiPreviewCard card, String? value) write,
    bool hasDefault = false,
  }) {
    final String id = 'hui-preview-card-$key';
    void set(String? next) => _mutate('card $key', (HuiPreviewDoc d) {
      final HuiPreviewCard? card = d.card;
      if (card != null) write(card, next);
    });
    return Field(
      label: label,
      forId: id,
      docKey: 'preview.card.$key',
      defaultValue: hasDefault ? fallback : null,
      onReset: value == null ? null : () => set(null),
      problems: _issuesFor('card.$key'),
      child: ColorField(
        id: id,
        label: label,
        value: value ?? fallback,
        onChanged: set,
      ),
    );
  }

  // --- settings, install, extras -------------------------------------------

  Widget _settingsSection() => AppSection(
    title: huiText('Settings'),
    memoryKey: 'preview.settings',
    children: <Widget>[
      Field(
        label: huiText('Scale'),
        forId: 'hui-preview-scale',
        docKey: 'preview.scale',
        defaultValue: '0.65',
        onReset: _doc.scale == 0.65
            ? null
            : () => _mutate('scale', (HuiPreviewDoc d) => d.scale = 0.65),
        problems: _issuesFor('scale'),
        child: NumberField(
          id: 'hui-preview-scale',
          value: _doc.scale,
          min: 0.25,
          max: 4,
          decimals: 2,
          onChanged: (double value) =>
              _mutate('scale', (HuiPreviewDoc d) => d.scale = value),
        ),
      ),
      Field(
        label: huiText('View distance'),
        forId: 'hui-preview-view-distance',
        docKey: 'preview.viewDistance',
        defaultValue: '10',
        onReset: _doc.viewDistance == 10
            ? null
            : () => _mutate(
                'viewDistance',
                (HuiPreviewDoc d) => d.viewDistance = 10,
              ),
        problems: _issuesFor('viewDistance'),
        child: NumberField(
          id: 'hui-preview-view-distance',
          value: _doc.viewDistance,
          min: 1,
          max: 24,
          step: 0.5,
          decimals: 2,
          onChanged: (double value) => _mutate(
            'viewDistance',
            (HuiPreviewDoc d) => d.viewDistance = value,
          ),
        ),
      ),
      _ticksField(
        id: 'hui-preview-content-refresh',
        label: huiText('Content refresh ticks'),
        docKey: 'preview.contentRefreshTicks',
        path: 'contentRefreshTicks',
        value: _doc.contentRefreshTicks,
        fallback: 4,
        write: (HuiPreviewDoc d, int? value) => d.contentRefreshTicks = value,
      ),
      _ticksField(
        id: 'hui-preview-access-check',
        label: huiText('Access check ticks'),
        docKey: 'preview.accessCheckTicks',
        path: 'accessCheckTicks',
        value: _doc.accessCheckTicks,
        fallback: 10,
        write: (HuiPreviewDoc d, int? value) => d.accessCheckTicks = value,
      ),
    ],
  );

  Widget _ticksField({
    required String id,
    required String label,
    required String docKey,
    required String path,
    required int? value,
    required int fallback,
    required void Function(HuiPreviewDoc doc, int? value) write,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    defaultValue: '$fallback',
    onReset: value == null
        ? null
        : () => _mutate(path, (HuiPreviewDoc d) => write(d, null)),
    problems: _issuesFor(path),
    child: NumberField(
      id: id,
      value: (value ?? fallback).toDouble(),
      min: 1,
      max: 1200,
      integer: true,
      onChanged: (double next) =>
          _mutate(path, (HuiPreviewDoc d) => write(d, next.round())),
    ),
  );

  Widget _install() => FieldGroup(
    title: huiText('Install on the server'),
    children: <Widget>[
      Field(
        label: huiText('Preview file'),
        child: _code('$huiPreviewFolder${store.menuId}.json'),
      ),
      Field(label: huiText('Permission'), child: _code('gloss.preview')),
    ],
  );

  static Widget _code(String text) => dom.code(
    attributes: const <String, String>{'dir': 'ltr'},
    <Widget>[Component.text(text)],
  );

  Widget _extras() => AppSection(
    title: huiText('Extra keys'),
    memoryKey: 'previewDoc.extras',
    open: false,
    children: <Widget>[
      ExtrasEditor(
        title: huiText('Document'),
        extras: _doc.extras,
        onChanged: (String label, Map<String, dynamic> next) =>
            _mutate(label, (HuiPreviewDoc d) => d.extras = next),
      ),
    ],
  );
}

/// `match.blocks`/`match.entities` (and their variant twins) as one comma
/// list. What is being typed stays as typed; the committed list is the
/// parsed, upper-cased names, and the text tidies to it on blur.
class _NameListField extends StatefulWidget {
  const _NameListField({
    required this.id,
    required this.label,
    required this.values,
    required this.onChanged,
    this.docKey,
    this.placeholder,
    this.problems = const <HuiIssue>[],
  });

  final String id;
  final String label;
  final List<String> values;
  final void Function(List<String> next) onChanged;
  final String? docKey;
  final String? placeholder;
  final List<HuiIssue> problems;

  @override
  State<_NameListField> createState() => _NameListFieldState();
}

class _NameListFieldState extends State<_NameListField> {
  /// The text being typed; null shows the committed names.
  String? _draft;

  @override
  void didUpdateWidget(_NameListField oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Undo, redo and a code-view commit rewrite the list behind the field.
    final String? draft = _draft;
    if (draft != null &&
        !_sameNames(parsePreviewNameList(draft), component.values)) {
      _draft = null;
    }
  }

  void _input(String text) {
    setState(() => _draft = text);
    final List<String> next = parsePreviewNameList(text);
    if (!_sameNames(next, component.values)) component.onChanged(next);
  }

  @override
  Widget build(BuildContext context) => Field(
    label: component.label,
    forId: component.id,
    tip: tipWithDoc(
      huiText('Separate names with commas; "*" is the only wildcard.'),
      component.docKey,
    ),
    problems: component.problems,
    child: TextInput(
      id: component.id,
      value: _draft ?? formatPreviewNameList(component.values),
      size: controlSize(DensityScope.of(context)),
      fullWidth: true,
      placeholder: component.placeholder,
      onChanged: _input,
      onBlur: () {
        if (_draft != null) setState(() => _draft = null);
      },
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );
}

/// Key/value rows for `vars`: value text with a colour swatch for anything
/// starting with `#`. A value commits as `true`/`false`/a number when the
/// text is exactly that; anything else (including a `#RRGGBB` literal, left
/// exactly as typed) is a plain string, matching
/// `PreviewDocumentParser.compileVars`.
class _PreviewVarsRows extends StatefulWidget {
  const _PreviewVarsRows({
    required this.idPrefix,
    required this.pathPrefix,
    required this.vars,
    required this.onChanged,
    this.issues = const <HuiIssue>[],
    this.docKey,
  });

  final String idPrefix;

  /// The issue path of the map (`match.vars`, `variants[0].vars`).
  final String pathPrefix;
  final Map<String, dynamic> vars;
  final void Function(Map<String, dynamic> next) onChanged;
  final List<HuiIssue> issues;

  /// Field-doc key on the add row.
  final String? docKey;

  @override
  State<_PreviewVarsRows> createState() => _PreviewVarsRowsState();
}

class _PreviewVarsRowsState extends State<_PreviewVarsRows> {
  final Map<String, String> _drafts = <String, String>{};
  String _newKey = '';
  String Function()? _newKeyError;

  @override
  void didUpdateWidget(_PreviewVarsRows oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Undo, redo and a code-view commit rewrite `vars` behind this widget.
    // A key whose committed value moved drops its stale draft, so the next
    // keystroke does not re-revert what was just undone.
    final Map<String, dynamic> next = component.vars;
    _drafts.removeWhere((String key, String draft) {
      if (!next.containsKey(key)) return true;
      final Object? was = oldComponent.vars[key];
      final Object? now = next[key];
      return _format(was) != _format(now);
    });
  }

  String _valueText(String key) => _drafts[key] ?? _format(component.vars[key]);

  static String _format(Object? value) {
    if (value == null) return '';
    if (value is bool) return value ? 'true' : 'false';
    if (value is num) {
      final double d = value.toDouble();
      return d == d.roundToDouble() && d.isFinite
          ? d.toInt().toString()
          : d.toString();
    }
    return value.toString();
  }

  static Object _encode(String text) {
    final String trimmed = text.trim();
    final String lower = trimmed.toLowerCase();
    if (lower == 'true') return true;
    if (lower == 'false') return false;
    final double? number = double.tryParse(trimmed);
    if (number != null) return number;
    return trimmed;
  }

  void _editValue(String key, String raw) {
    setState(() => _drafts[key] = raw);
    final Map<String, dynamic> next = Map<String, dynamic>.of(component.vars);
    next[key] = _encode(raw);
    component.onChanged(next);
  }

  void _remove(String key) {
    _drafts.remove(key);
    final Map<String, dynamic> next = Map<String, dynamic>.of(component.vars)
      ..remove(key);
    setState(() {});
    component.onChanged(next);
  }

  void _add() {
    final String key = _newKey.trim();
    if (key.isEmpty) {
      setState(() => _newKeyError = () => huiText('Give the var a name.'));
      return;
    }
    if (!_identifierPattern.hasMatch(key)) {
      setState(
        () =>
            _newKeyError = () =>
                huiText('Letters, digits and underscore only.'),
      );
      return;
    }
    if (component.vars.containsKey(key)) {
      setState(
        () => _newKeyError = () =>
            huiText('{key} is already here.', <String, Object?>{'key': key}),
      );
      return;
    }
    setState(() {
      _newKey = '';
      _newKeyError = null;
    });
    final Map<String, dynamic> next = Map<String, dynamic>.of(component.vars);
    next[key] = 0.0;
    component.onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    return Component.fragment(<Widget>[
      for (final String key in component.vars.keys.toList()) _row(key, size),
      _addRow(size),
    ]);
  }

  Widget _row(String key, ComponentSize size) {
    final Object? value = component.vars[key];
    final String id = '${component.idPrefix}-$key';
    final String path = '${component.pathPrefix}.$key';
    final Widget? swatch = value is String && value.startsWith('#')
        ? previewColorPicker(
            value,
            label: huiText("{key} colour", <String, Object?>{'key': key}),
            onPicked: (String hex) => _editValue(key, hex),
          )
        : null;
    return Field(
      label: key,
      forId: id,
      problems: component.issues
          .where((HuiIssue issue) => issue.path == path)
          .toList(),
      trailing: Component.fragment(<Widget>[
        ?swatch,
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText("Remove {key}", <String, Object?>{'key': key}),
          tipAtEnd: true,
          onPressed: () => _remove(key),
        ),
      ]),
      child: TextInput(
        id: id,
        value: _valueText(key),
        size: size,
        fullWidth: true,
        placeholder: huiText('value or #RRGGBB'),
        onChanged: (String text) => _editValue(key, text),
        attributes: const <String, String>{
          'autocomplete': 'off',
          'spellcheck': 'false',
        },
      ),
    );
  }

  Widget _addRow(ComponentSize size) {
    final String id = '${component.idPrefix}-new';
    return Field(
      label: huiText('Add a var'),
      forId: id,
      docKey: component.docKey,
      error: _newKeyError?.call(),
      trailing: AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add'),
        tipAtEnd: true,
        onPressed: _add,
      ),
      child: TextInput(
        id: id,
        value: _newKey,
        size: size,
        fullWidth: true,
        placeholder: huiText('accent'),
        onChanged: (String text) => setState(() {
          _newKey = text;
          _newKeyError = null;
        }),
        onSubmitted: (String _) => _add(),
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
      ),
    );
  }
}
