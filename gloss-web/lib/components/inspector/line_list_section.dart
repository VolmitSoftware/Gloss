/// The list-of-text-lines section, shared by every document kind that has one.
///
/// Holograms, animations, scoreboards and MOTD entries all edit the same thing:
/// an ordered list of pipeline strings, each row an input, a rendered preview
/// and a delete. This is one row and one list, with the per-kind parts (the
/// label, the doc key, the pickers, what a preview looks like) passed in.
/// The section is a kit [AppSection]; the rows sit in a [ReorderList].
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/tone.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// One chip per `|animation.<id>|` reference no document in this workspace
/// answers. The text stays literal in game, which the rendered preview beside
/// it cannot show on its own — it has nothing to substitute, so it prints the
/// reference and looks like a deliberate choice.
///
/// [where] finishes the sentence: `in game`, `in the server list`.
List<Widget> huiMissingAnimationChips(
  String line,
  GlossAnimationResolver animations, {
  required String where,
}) => <Widget>[
  for (final String reference in glossLineMissingAnimationRefs(
    line,
    animations,
  ))
    TipLabel(
      label: StatusBadge(
        huiText("missing {reference}", <String, Object?>{
          'reference': reference,
        }),
        tone: BadgeTone.warning,
      ),
      tip: huiText(
        "No animation document named \"{substring}\" exists in this workspace; the text shows literally {where}.",
        <String, Object?>{
          'substring': reference.substring(glossAnimationFunctionPrefix.length),
          'where': where,
        },
      ),
    ),
];

/// One row: the input, whatever the kind renders as its preview, and the
/// row's tools: in an inline-tools [ReorderList] the grip and move buttons,
/// then remove.
class LineRow extends StatelessWidget {
  const LineRow({
    required this.value,
    required this.placeholder,
    required this.onChanged,
    required this.onRemove,
    required this.removeLabel,
    this.label,
    this.onFocus,
    this.preview,
    this.chips = const <Widget>[],
    this.beyondRender = false,
    super.key,
  });

  final String value;
  final String placeholder;
  final void Function(String value) onChanged;
  final void Function() onRemove;

  /// Accessible name for the delete button — `Delete line 3`, not `Delete`.
  final String removeLabel;

  /// Accessible name for the input (`Line 3`); the placeholder is no name.
  final String? label;

  /// Tells the owning inspector which row a picker should insert into.
  final void Function()? onFocus;

  /// The rendered line, when the kind has a preview pipeline for it.
  final Widget? preview;

  /// Warnings that belong beside the preview rather than under the field: a
  /// missing animation reference, a row past the kind's render limit.
  final List<Widget> chips;

  /// Marks the row as one the server will not draw. Styling only; the note
  /// itself belongs in [chips], where it can say why.
  final bool beyondRender;

  @override
  Widget build(BuildContext context) {
    final bool previewed = preview != null || chips.isNotEmpty;
    final Widget remove = AppIconButton(
      label: removeLabel,
      icon: ArcaneIcon.trash2(),
      onPressed: onRemove,
    );
    // The input takes the whole row, as wide as every other field; the
    // row's tools (grip, move, remove) sit together beside the preview
    // under it, so a narrow inspector still leaves room to type.
    final List<Widget> tools = <Widget>[const ReorderRowTools(), remove];
    return dom.div(
      classes: beyondRender ? 'hui-line-row is-beyond-render' : 'hui-line-row',
      <Widget>[
        dom.div(classes: 'hui-line-row-edit', <Widget>[
          TextInput(
            value: value,
            size: controlSize(DensityScope.of(context)),
            fullWidth: true,
            placeholder: placeholder,
            onChanged: onChanged,
            onFocus: onFocus,
            attributes: <String, String>{
              'aria-label': ?label,
              'autocomplete': 'off',
              'spellcheck': 'false',
            },
          ),
          if (!previewed) ...tools,
        ]),
        if (previewed)
          dom.div(classes: 'hui-line-row-edit', <Widget>[
            dom.div(classes: 'hui-line-row-preview', <Widget>[
              ?preview,
              ...chips,
            ]),
            ...tools,
          ]),
      ],
    );
  }
}

/// The section around those rows: add button and kind-specific pickers on
/// the title line, the reorderable list, and the empty state that says what
/// silence looks like in game.
class LineListSection extends StatelessWidget {
  const LineListSection({
    required this.title,
    required this.addLabel,
    required this.onAdd,
    required this.itemCount,
    required this.itemBuilder,
    this.emptyBody,
    this.onReorder,
    this.docKey,
    this.description,
    this.tools = const <Widget>[],
    this.footer = const <Widget>[],
    this.issues = const <HuiIssue>[],
    this.sectionKey,
    super.key,
  });

  final String title;
  final String addLabel;
  final void Function() onAdd;
  final int itemCount;
  final Widget Function(int index) itemBuilder;

  /// What an empty list means in game, the tip beside "Nothing here yet":
  /// the consequence first, so it is information rather than a scold.
  final String? emptyBody;

  /// Null keeps the list in document order with no move buttons: the MOTD
  /// entry's own one-or-two lines are not worth reordering.
  final void Function(int from, int to)? onReorder;

  /// Key into the field docs, the section's (i) tip.
  final String? docKey;

  /// A short line under the title; a sentence belongs in the tip.
  final String? description;

  /// Pickers on the title line, before Add.
  final List<Widget> tools;

  /// Rendered under the list, inside the section.
  final List<Widget> footer;
  final List<HuiIssue> issues;

  /// Keeps the section's open state across documents.
  final String? sectionKey;

  @override
  Widget build(BuildContext context) => AppSection(
    title: title,
    count: itemCount,
    docKey: docKey,
    description: description,
    memoryKey: sectionKey,
    actions: <Widget>[
      ...tools,
      AppIconButton(icon: ArcaneIcon.plus(), label: addLabel, onPressed: onAdd),
    ],
    children: <Widget>[
      // What an empty list means in game is behind the (i), so the empty
      // section stays one short line.
      if (itemCount == 0)
        ArcaneFieldDescription.child(
          TipLabel(label: Text(huiText('Nothing here yet')), tip: emptyBody),
        )
      else if (onReorder == null)
        dom.div(classes: 'hui-line-list', <Widget>[
          for (int index = 0; index < itemCount; index++) itemBuilder(index),
        ])
      else
        ReorderList(
          label: title,
          itemCount: itemCount,
          onReorder: onReorder!,
          itemBuilder: itemBuilder,
          inlineTools: true,
        ),
      ...footer,
      for (final HuiIssue issue in distinctIssues(issues))
        issueNote(issue),
    ],
  );
}
