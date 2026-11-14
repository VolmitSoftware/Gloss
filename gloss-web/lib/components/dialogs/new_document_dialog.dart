/// The one place a document starts: blank, from a template, or random from
/// a seed. Kinds sit under the six kind groups (`config/kind_groups.dart`);
/// Simple leaves out the Advanced kinds. A click creates at once through
/// [NewDocumentDialog.onCreate], whose owner closes the dialog. It stays
/// mounted and follows [NewDocumentDialog.open], so focus moves to the first
/// choice on open and back to the opener on close.
library;

import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../config/kind_groups.dart';
import '../../doctype/doctype.dart';
import '../../l10n/hui_localizations.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/toggle_choice.dart';
import 'new_document_choice.dart';

export 'new_document_choice.dart'
    show NewDocumentChoice, NewDocumentStart, createNewDocument;

const int _seedLimit = 2147483647;

int _freshSeed() => math.Random().nextInt(_seedLimit);

class NewDocumentDialog extends StatefulWidget {
  const NewDocumentDialog({
    required this.open,
    required this.onClose,
    required this.onCreate,
    this.start = NewDocumentStart.blank,
    super.key,
  });

  static const String dialogId = 'hui-new-document-dialog';
  static const String triggerId = 'hui-library-new-document';
  static const String firstChoiceId = 'hui-new-document-first-choice';
  static const String _seedId = 'hui-new-document-seed';

  final bool open;
  final void Function() onClose;
  final void Function(NewDocumentChoice choice) onCreate;

  /// The start option shown when it opens (File > Templates opens it on
  /// template).
  final NewDocumentStart start;

  @override
  State<NewDocumentDialog> createState() => _NewDocumentDialogState();
}

class _NewDocumentDialogState extends State<NewDocumentDialog> {
  late NewDocumentStart _start = component.start;
  int _seed = _freshSeed();

  @override
  void initState() {
    super.initState();
    if (component.open) _focusFirstChoice();
  }

  @override
  void didUpdateWidget(NewDocumentDialog oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.open && !oldComponent.open) {
      _start = component.start;
      _seed = _freshSeed();
      _focusFirstChoice();
    }
  }

  /// The dialog's own focus lands on the first field (the seed in Advanced
  /// random); the first choice is what a keyboard user wants, so it wins
  /// once the panel is drawn.
  void _focusFirstChoice() {
    void focus() {
      if (!mounted || !component.open) return;
      final web.Element? active = web.document.activeElement;
      if (active != null && active.matches('[data-new-choice]')) return;
      (web.document.getElementById(
        NewDocumentDialog.firstChoiceId,
      ) as web.HTMLElement?)?.focus();
    }

    web.window.requestAnimationFrame(((JSNumber _) => focus()).toJS);
    web.window.setTimeout((() => focus()).toJS, 80.toJS);
  }

  void _create(DocumentTypeAdapter type, {DocumentTemplate? template}) {
    final int? seed = _start != NewDocumentStart.random
        ? null
        : ModeScope.advanced(context)
        ? _seed
        : _freshSeed();
    component.onCreate(NewDocumentChoice(type, template: template, seed: seed));
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<NewDocumentGroup> groups = newDocumentGroups(
      _start,
      advanced: advanced,
    );
    final DocumentTypeAdapter? firstType = groups.isEmpty
        ? null
        : groups.first.types.first;
    return AppDialog(
      id: NewDocumentDialog.dialogId,
      open: component.open,
      title: huiText('New document'),
      onClose: component.onClose,
      wide: true,
      footer: DialogFooter(
        cancel: RowAction(huiText('Cancel'), component.onClose),
      ),
      children: <Widget>[
        ToggleChoice<NewDocumentStart>(
          label: huiText('Start from'),
          value: _start,
          onChanged: (NewDocumentStart start) => setState(() => _start = start),
          options: <ChoiceOption<NewDocumentStart>>[
            ChoiceOption<NewDocumentStart>(
              NewDocumentStart.blank,
              huiText('Blank'),
              icon: ArcaneIcon.file(),
            ),
            ChoiceOption<NewDocumentStart>(
              NewDocumentStart.template,
              huiText('Template'),
              icon: ArcaneIcon.layoutTemplate(),
            ),
            ChoiceOption<NewDocumentStart>(
              NewDocumentStart.random,
              huiText('Random'),
              icon: ArcaneIcon.dices(),
            ),
          ],
        ),
        if (_start == NewDocumentStart.random && advanced) _seedField(),
        for (final NewDocumentGroup group in groups)
          FieldGroup(
            title: group.group.label,
            children: <Widget>[
              if (_start == NewDocumentStart.template)
                for (final DocumentTypeAdapter type in group.types)
                  ..._templates(type, first: identical(type, firstType))
              else
                dom.div(classes: 'hui-new-choices', <Widget>[
                  for (final DocumentTypeAdapter type in group.types)
                    _NewChoice(
                      label: kindInfo(type.kind).singular,
                      icon: type.createIcon(),
                      tip: kindInfo(type.kind).tip,
                      id: identical(type, firstType)
                          ? NewDocumentDialog.firstChoiceId
                          : null,
                      onPressed: () => _create(type),
                    ),
                ]),
            ],
          ),
        // The body scrolls as one region between the fixed title and
        // footer; this strip fades its bottom edge while more follows.
        const dom.div(
          classes: 'hui-new-more',
          attributes: <String, String>{'aria-hidden': 'true'},
          <Widget>[],
        ),
      ],
    );
  }

  List<Widget> _templates(DocumentTypeAdapter type, {required bool first}) {
    final List<DocumentTemplate> templates = templatesOf(type);
    return <Widget>[
      dom.p(classes: 'ui-section-description', <Widget>[
        Text(kindInfo(type.kind).label),
      ]),
      dom.div(classes: 'hui-new-choices', <Widget>[
        for (final DocumentTemplate template in templates)
          _NewChoice(
            label: huiText(template.name),
            tip: huiText(template.description),
            id: first && identical(template, templates.first)
                ? NewDocumentDialog.firstChoiceId
                : null,
            onPressed: () => _create(type, template: template),
          ),
      ]),
    ];
  }

  Widget _seedField() => Field(
    label: huiText('Seed'),
    forId: NewDocumentDialog._seedId,
    tip: huiText(
      'The same seed and workspace assets reproduce the same document. Undo restores the previous version.',
    ),
    trailing: AppIconButton(
      icon: ArcaneIcon.dices(),
      label: huiText('New seed'),
      onPressed: () => setState(() => _seed = _freshSeed()),
    ),
    child: NumberField(
      id: NewDocumentDialog._seedId,
      value: _seed.toDouble(),
      integer: true,
      decimals: 0,
      step: 1,
      steppers: false,
      min: 0,
      max: _seedLimit.toDouble(),
      onChanged: (double value) =>
          setState(() => _seed = value.round().clamp(0, _seedLimit)),
    ),
  );
}

/// One choice: a full-width outline button named by what it makes, its
/// one-line tip as the tooltip, so the choices form an even grid.
class _NewChoice extends StatelessWidget {
  const _NewChoice({
    required this.label,
    required this.tip,
    required this.onPressed,
    this.icon,
    this.id,
  });

  static const ArcaneStyleData _cell = ArcaneStyleData(
    justifyContent: JustifyContent.flexStart,
  );

  final String label;
  final String tip;
  final ArcaneGlyph? icon;
  final String? id;
  final void Function() onPressed;

  @override
  Widget build(BuildContext context) => ArcaneTooltip(
    text: tip,
    child: Button(
      id: id,
      variant: ButtonVariant.outline,
      label: label,
      icon: icon?.sized(controlIconSize),
      fullWidth: true,
      styles: _cell,
      attributes: const <String, String>{'data-new-choice': ''},
      onPressed: onPressed,
    ),
  );
}
