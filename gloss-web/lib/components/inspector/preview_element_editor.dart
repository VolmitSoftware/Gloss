/// Per-element inspector for a container-preview document: the shared
/// x/y/z/visible/repeat fields every element type carries, plus the fields
/// specific to `panel`/`cell`/`slot`/`label`.
///
/// Every expression-capable field is a [PreviewExprField]; there is no
/// separate constant or expression mode to pick. Typing a bare number or
/// `true`/`false` commits the JSON constant shape, anything else commits an
/// expression string, and both compile the same: the JSON already erases the
/// distinction (`x: 5` and `x: "5"` behave the same at build time).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../logic/preview_card_edit.dart' show previewAutoSimCategory;
import '../../logic/preview_doc_validation.dart';
import '../../logic/preview_sim.dart' show previewCategoryVariableNames;
import '../../logic/validation.dart';
import '../../model/gloss_hologram_box.dart';
import '../../model/hui_icons.dart';
import '../../model/preview_doc.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/tone.dart';
import 'display_style_editor.dart';
import 'extras_editor.dart';
import 'hologram_box_editor.dart';
import 'preview_color_swatch.dart';
import 'preview_expr_field.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

class PreviewElementEditor extends StatelessWidget {
  const PreviewElementEditor({
    required this.store,
    required this.index,
    required this.element,
    super.key,
  });

  final EditorStore store;
  final int index;
  final HuiPreviewElement element;

  String get _path => 'elements[$index]';

  List<HuiIssue> get _elementIssues => store.issuesForPreviewElement(index);

  List<HuiIssue> _issuesFor(String field) => _elementIssues
      .where((HuiIssue issue) => issue.path == '$_path.$field')
      .toList();

  Set<String> get _declaredVars {
    final HuiPreviewDoc? doc = store.previewDoc;
    return doc == null ? const <String>{} : previewDeclaredVars(doc);
  }

  Set<String> get _scope {
    final HuiPreviewRepeat? repeat = element.repeat;
    if (repeat == null) return const <String>{};
    final String name = (repeat.varName == null || repeat.varName!.isEmpty)
        ? 'i'
        : repeat.varName!;
    return <String>{name};
  }

  /// Every variable name the document's own auto-detected simulated category
  /// publishes, behind each field's "not published here" note. Empty (which
  /// silences the note) when no preview document is open.
  ///
  /// [previewCategoryVariableNames] is a pure function of the category
  /// string: this pane rebuilds on every simulation tick while a card
  /// animates and reads this once per field, so building a whole
  /// `PreviewSim` here would allocate dozens of throwaway states a second.
  List<String> get _categoryVariableNames {
    final HuiPreviewDoc? doc = store.previewDoc;
    if (doc == null) return const <String>[];
    return previewCategoryVariableNames(previewAutoSimCategory(doc));
  }

  void _mutate(String label, void Function(HuiPreviewElement e) fn) =>
      store.editPreviewElement(index, label, fn);

  String get _name =>
      huiText('Element {value}', <String, Object?>{'value': index + 1});

  void _delete() {
    final String name = _name;
    store.deletePreviewElement(index);
    toastInfo(
      huiText('Deleted {id}.', <String, Object?>{'id': name}),
      action: ToastAction(
        label: huiText('Undo'),
        onPressed: () {
          store.performUndo();
        },
      ),
    );
  }

  double _defaultZ() => switch (element.type) {
    'panel' => 1,
    'cell' || 'slot' => 4,
    'label' => 6,
    _ => 0,
  };

  @override
  Widget build(BuildContext context) => DenseBox(
    classes: 'hui-inspector-body is-preview-element',
    children: <Widget>[
      PaneHeader(
        kind: _typeLabel(element.type),
        title: _name,
        tip: switch (previewElementTypeDescriptions[element.type]) {
          final String note => huiText(note),
          null => null,
        },
        status: _status(),
        menuId: 'hui-inspector-menu',
        actions: <AppMenuEntry>[
          AppMenuEntry(
            huiText('Duplicate'),
            () => store.duplicatePreviewElement(index),
            icon: ArcaneIcon.copy(),
          ),
          const AppMenuEntry.separator(),
          AppMenuEntry(
            huiText('Delete'),
            _delete,
            icon: ArcaneIcon.trash2(),
            destructive: true,
          ),
        ],
      ),
      if (!previewElementTypeDescriptions.containsKey(element.type))
        Field(
          label: huiText('Type'),
          docKey: 'preview.element.type',
          problems: _issuesFor('type'),
          child: dom.code(
            attributes: const <String, String>{'dir': 'ltr'},
            <Widget>[
              Component.text(
                element.type.isEmpty ? huiText('(no type)') : element.type,
              ),
            ],
          ),
        ),
      _placement(),
      ?_typeSection(),
      _repeatSection(context),
      DisplayStyleEditor(
        style: element.style,
        defaults: element.type == 'slot'
            ? (store.previewDoc?.itemStyle ??
                  HuiIconStyle(blockLight: 15, skyLight: 15))
            : store.previewDoc?.textStyle,
        issues: _elementIssues
            .where((HuiIssue issue) => issue.path.startsWith('$_path.style'))
            .toList(),
        onChanged: (String label, HuiIconStyle? style) => _mutate(
          label,
          (HuiPreviewElement edited) => edited.style = style,
        ),
      ),
      if (element.type == 'label')
        HologramBoxEditor(
          box: element.box ?? GlossHologramBox(),
          issues: _issuesFor('box'),
          mutate: (String label, void Function(GlossHologramBox) edit) =>
              _mutate(label, (HuiPreviewElement edited) {
                edited.box ??= GlossHologramBox();
                edit(edited.box!);
              }),
        ),
      AdvancedOnly(child: _extras()),
    ],
  );

  /// Error and warning counts on this element, as badges after the title.
  Widget? _status() {
    final int errors = _elementIssues
        .where((HuiIssue i) => i.severity == HuiSeverity.error)
        .length;
    final int warnings = _elementIssues
        .where((HuiIssue i) => i.severity == HuiSeverity.warning)
        .length;
    if (errors == 0 && warnings == 0) return null;
    return Component.fragment(<Widget>[
      if (errors > 0)
        StatusBadge(
          huiPlural(
            'element.problem_count',
            errors,
            oneEnglish: '{count} problem',
            otherEnglish: '{count} problems',
          ),
          tone: BadgeTone.error,
        ),
      if (warnings > 0)
        StatusBadge(
          huiPlural(
            'element.warning_count',
            warnings,
            oneEnglish: '{count} warning',
            otherEnglish: '{count} warnings',
          ),
          tone: BadgeTone.warning,
        ),
    ]);
  }

  static String _typeLabel(String type) => switch (type) {
    'panel' => huiText('Panel'),
    'cell' => huiText('Cell'),
    'slot' => huiText('Slot'),
    'label' => huiText('Label'),
    _ => type.isEmpty ? huiText('(no type)') : type,
  };

  Widget _placement() => AppSection(
    title: huiText('Placement'),
    memoryKey: 'preview.placement',
    children: <Widget>[
      _field(
        'X',
        element.x,
        (Object? v) => _mutate('x', (HuiPreviewElement e) => e.x = v),
        field: 'x',
        docKey: 'preview.element.x',
        placeholder: '0',
      ),
      _field(
        'Y',
        element.y,
        (Object? v) => _mutate('y', (HuiPreviewElement e) => e.y = v),
        field: 'y',
        docKey: 'preview.element.y',
        placeholder: '0',
      ),
      _field(
        'Z',
        element.z,
        (Object? v) => _mutate('z', (HuiPreviewElement e) => e.z = v),
        field: 'z',
        docKey: 'preview.element.z',
        placeholder: _formatDefault(_defaultZ()),
      ),
      _field(
        huiText('Visible'),
        element.visible,
        (Object? v) =>
            _mutate('visible', (HuiPreviewElement e) => e.visible = v),
        field: 'visible',
        docKey: 'preview.element.visible',
        kind: PreviewExprKind.boolean,
        placeholder: huiText('true'),
      ),
      AdvancedOnly(
        child: _field(
          huiText('Visibility expression'),
          element.show,
          (Object? value) => _mutate(
            'show',
            (HuiPreviewElement element) => element.show = value,
          ),
          field: 'show',
          showCondition: true,
          docKey: 'preview.element.show',
          kind: PreviewExprKind.boolean,
          placeholder: huiText('true'),
        ),
      ),
    ],
  );

  static String _formatDefault(double value) => value == value.roundToDouble()
      ? value.toInt().toString()
      : value.toString();

  Widget _repeatSection(BuildContext context) {
    final HuiPreviewRepeat? repeat = element.repeat;
    const String varId = 'hui-preview-repeat-var';
    return AppSection(
      title: huiText('Repeat'),
      memoryKey: 'preview.repeat',
      docKey: 'preview.element.repeat',
      children: <Widget>[
        SwitchField(
          label: huiText('Repeat this element'),
          value: repeat != null,
          onChanged: (bool value) => _mutate(
            value ? 'add repeat' : 'remove repeat',
            (HuiPreviewElement e) => e.repeat = value
                ? HuiPreviewRepeat(count: 2, varName: 'i')
                : null,
          ),
        ),
        if (repeat != null) ...<Widget>[
          PreviewExprField(
            label: huiText('Count'),
            raw: repeat.count,
            required: true,
            placeholder: '2',
            docKey: 'preview.repeat.count',
            issues: _issuesFor('repeat.count'),
            declaredVars: _declaredVars,
            onChanged: (Object? v) => _mutate(
              'repeat count',
              (HuiPreviewElement e) => e.repeat?.count = v,
            ),
          ),
          Field(
            label: huiText('Loop variable'),
            forId: varId,
            docKey: 'preview.repeat.var',
            defaultValue: 'i',
            onReset: (repeat.varName ?? 'i') == 'i'
                ? null
                : () => _mutate(
                    'repeat var',
                    (HuiPreviewElement e) => e.repeat?.varName = 'i',
                  ),
            problems: _issuesFor('repeat.var'),
            child: TextInput(
              id: varId,
              value: repeat.varName ?? 'i',
              size: controlSize(DensityScope.of(context)),
              fullWidth: true,
              placeholder: huiText('i'),
              onChanged: (String value) => _mutate(
                'repeat var',
                (HuiPreviewElement e) => e.repeat?.varName = value,
              ),
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
            ),
          ),
        ],
      ],
    );
  }

  /// The fields only this element type has; null for an unknown type (the
  /// Type field under the header says what is wrong).
  Widget? _typeSection() => switch (element.type) {
    'panel' => _typeFields(<Widget>[
      _field(
        huiText('Width'),
        element.width,
        (Object? v) => _mutate('width', (HuiPreviewElement e) => e.width = v),
        field: 'width',
        docKey: 'preview.element.width',
        required: true,
        placeholder: '40',
      ),
      _field(
        huiText('Height'),
        element.height,
        (Object? v) => _mutate('height', (HuiPreviewElement e) => e.height = v),
        field: 'height',
        docKey: 'preview.element.height',
        required: true,
        placeholder: '20',
      ),
      _field(
        huiText('Color'),
        element.color,
        (Object? v) => _mutate('color', (HuiPreviewElement e) => e.color = v),
        field: 'color',
        docKey: 'preview.element.color',
        required: true,
        placeholder: huiText('#FF15151B'),
        colorLabel: huiText('panel colour'),
      ),
    ]),
    'cell' => _typeFields(<Widget>[
      _field(
        huiText('Size'),
        element.size,
        (Object? v) => _mutate('size', (HuiPreviewElement e) => e.size = v),
        field: 'size',
        docKey: 'preview.element.size',
        required: true,
        placeholder: '8',
      ),
      _field(
        huiText('Color'),
        element.color,
        (Object? v) => _mutate('color', (HuiPreviewElement e) => e.color = v),
        field: 'color',
        docKey: 'preview.element.color',
        required: true,
        placeholder: huiText('#FF15151B'),
        colorLabel: huiText('cell colour'),
      ),
    ]),
    'slot' => _typeFields(<Widget>[
      _field(
        huiText('Size'),
        element.size,
        (Object? v) => _mutate('size', (HuiPreviewElement e) => e.size = v),
        field: 'size',
        docKey: 'preview.element.size',
        required: true,
        placeholder: '18',
      ),
      _field(
        huiText('Index'),
        element.index,
        (Object? v) => _mutate('index', (HuiPreviewElement e) => e.index = v),
        field: 'index',
        docKey: 'preview.element.index',
        required: true,
        placeholder: '0',
      ),
      _field(
        huiText('Well color'),
        element.wellColor,
        (Object? v) =>
            _mutate('wellColor', (HuiPreviewElement e) => e.wellColor = v),
        field: 'wellColor',
        docKey: 'preview.element.wellColor',
        placeholder: huiText('#FF15151B (default)'),
        colorLabel: huiText('well colour'),
      ),
    ]),
    'label' => _typeFields(<Widget>[
      _field(
        huiText('Text'),
        element.text,
        (Object? v) =>
            _mutate('text', (HuiPreviewElement e) => e.text = v as String?),
        field: 'text',
        docKey: 'preview.element.text',
        required: true,
        kind: PreviewExprKind.string,
        placeholder: huiText("'Label text'"),
      ),
      _field(
        huiText('Background'),
        element.background,
        (Object? v) =>
            _mutate('background', (HuiPreviewElement e) => e.background = v),
        field: 'background',
        docKey: 'preview.element.background',
        placeholder: huiText('0 (transparent)'),
        colorLabel: huiText('label background'),
      ),
    ]),
    _ => null,
  };

  Widget _typeFields(List<Widget> fields) => AppSection(
    title: _typeLabel(element.type),
    memoryKey: 'preview.type.${element.type}',
    children: fields,
  );

  /// One [PreviewExprField], wired to this element's issues, declared vars,
  /// repeat scope and simulated-category names. [field] is the raw JSON key
  /// (`x`, `width`, ...), used only to filter [_issuesFor]. A colour field
  /// gets a swatch while its value is a constant colour: a live expression
  /// has no one colour to pick, and picking would overwrite it.
  Widget _field(
    String label,
    Object? raw,
    void Function(Object? value) onChanged, {
    required String field,
    bool required = false,
    bool showCondition = false,
    PreviewExprKind kind = PreviewExprKind.numeric,
    String? placeholder,
    String? docKey,
    String? colorLabel,
  }) => PreviewExprField(
    label: label,
    raw: raw,
    required: required,
    showCondition: showCondition,
    kind: kind,
    placeholder: placeholder,
    docKey: docKey,
    trailing: colorLabel == null
        ? null
        : previewColorPicker(
            raw,
            label: colorLabel,
            onPicked: (String hex) => onChanged(hex),
          ),
    issues: _issuesFor(field),
    declaredVars: _declaredVars,
    scope: _scope,
    categoryVariableNames: _categoryVariableNames,
    onChanged: onChanged,
  );

  Widget _extras() => AppSection(
    title: huiText('Extra keys'),
    memoryKey: 'preview.extras',
    open: false,
    children: <Widget>[
      ExtrasEditor(
        title: huiText('Element'),
        extras: element.extras,
        onChanged: (String label, Map<String, dynamic> next) =>
            _mutate(label, (HuiPreviewElement e) => e.extras = next),
      ),
    ],
  );
}
