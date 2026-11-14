/// The one control every expression-capable preview field uses: a text input
/// that stores a `num`/`bool` constant or an expression source string. A
/// draft that does not parse stays local and its syntax error shows under the
/// field; the browser's own suggestion list offers completions from the
/// catalog, the document's `vars` and the element's repeat scope
/// ([previewExprCompletions]).
///
/// Only a value [parsePreviewExpr] accepts reaches [PreviewExprField.onChanged],
/// so a keystroke never leaves the document worse off. Variable-name problems
/// come in as [PreviewExprField.issues] from [validatePreviewDoc]; the one
/// thing only this field knows is which simulated category is previewing, so
/// the "not published by this category" note is computed here from
/// [PreviewExprField.categoryVariableNames].
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gloss_show.dart';
import '../../logic/preview_doc_validation.dart';
import '../../logic/preview_expr.dart';
import '../../logic/preview_field_text.dart';
import '../../logic/validation.dart';
import '../../model/preview_doc.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/technical_input.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';

/// Which JSON shapes a bare (non-expression) draft is allowed to commit as.
/// The model pins this per field (`visible`/`framed` reject a JSON number on
/// decode, `text`/`title`/`accent` reject anything but a string), so getting
/// it wrong silently drops the value the next time the document round-trips
/// through the codec.
enum PreviewExprKind {
  /// `x`, `y`, `z`, `width`, `height`, `size`, `color`, `wellColor`, `index`,
  /// `background`, `repeat.count`: a bare number commits as a JSON number,
  /// anything else as an expression string.
  numeric,

  /// `visible`, `card.framed`: the bare words `true`/`false` commit as a JSON
  /// boolean, anything else as an expression string. Never a JSON number.
  boolean,

  /// `text`, `card.title`, `card.accent`: always a string, exactly as typed
  /// (or `null` for an empty draft). Even a literal color is written as the
  /// expression `'#RRGGBB'`.
  string,
}

class PreviewExprField extends StatefulWidget {
  const PreviewExprField({
    required this.label,
    required this.raw,
    required this.onChanged,
    this.kind = PreviewExprKind.numeric,
    this.required = false,
    this.showCondition = false,
    this.help,
    this.docKey,
    this.placeholder,
    this.trailing,
    this.issues = const <HuiIssue>[],
    this.declaredVars = const <String>{},
    this.scope = const <String>{},
    this.categoryVariableNames = const <String>[],
    super.key,
  });

  final String label;

  /// The field's current committed value: `num`, `bool`, `String` (an
  /// expression source), or `null` for absent (the compiler's own default
  /// applies). Which of these a bare draft may commit as is [kind].
  final Object? raw;

  final void Function(Object? next) onChanged;

  final PreviewExprKind kind;
  final bool required;
  final bool showCondition;

  /// The field's tip, behind its (i) button.
  final String? help;

  /// A field-doc key whose body is the tip when [help] is null.
  final String? docKey;
  final String? placeholder;

  /// A small widget at the end of the label line (a colour swatch).
  final Widget? trailing;

  /// Pre-filtered issues for this exact field path, straight from
  /// [validatePreviewDoc] via `EditorStore.issuesForPreviewElement` (or a
  /// `card.*`/`match.*` filter at the document level).
  final List<HuiIssue> issues;

  /// Names reachable as `vars.<name>`, offered as completions and never
  /// validated here (the store already flags an unknown one).
  final Set<String> declaredVars;

  /// The active element's repeat variable, if any, offered the same way.
  final Set<String> scope;

  /// Every variable name the currently simulated category publishes. Empty
  /// means unknown (no live preview open), which skips the note entirely
  /// rather than flagging every cataloged name as unpublished.
  final List<String> categoryVariableNames;

  @override
  State<PreviewExprField> createState() => _PreviewExprFieldState();
}

int _nextExprFieldId = 0;

class _PreviewExprFieldState extends State<PreviewExprField> {
  final String _id = 'hui-expr-${_nextExprFieldId++}';
  late String _text;

  /// The value this widget itself last committed, so an external change (undo,
  /// a code-view commit, another surface editing the same element) can be told
  /// apart from an echo of our own edit and only the former resyncs [_text].
  Object? _lastCommitted;

  String Function()? _syntaxError;
  List<String> _categoryHints = const <String>[];

  @override
  void initState() {
    super.initState();
    _lastCommitted = component.raw;
    _text = _format(component.raw);
  }

  @override
  void didUpdateWidget(PreviewExprField oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (component.raw == _lastCommitted) return;
    _lastCommitted = component.raw;
    _text = _format(component.raw);
    _syntaxError = null;
    _categoryHints = const <String>[];
  }

  static String _format(Object? raw) {
    if (raw == null) return '';
    if (raw is bool) return raw ? 'true' : 'false';
    if (raw is num) return previewStringify(raw.toDouble());
    return raw is String ? raw : jsonEncode(raw);
  }

  Object? _encode(String trimmed) {
    if (trimmed.isEmpty) return null;
    switch (component.kind) {
      case PreviewExprKind.boolean:
        final String lower = trimmed.toLowerCase();
        if (lower == 'true') return true;
        if (lower == 'false') return false;
        return trimmed;
      case PreviewExprKind.numeric:
        return double.tryParse(trimmed) ?? trimmed;
      case PreviewExprKind.string:
        return trimmed;
    }
  }

  void _onInput(String raw) {
    setState(() {
      _text = raw;
      _revalidate();
    });
  }

  static String Function() _describe(PExprException error) =>
      error.position == previewNoPosition
      ? () => error.message
      : () => huiText(
          '{message} at position {position}',
          <String, Object?>{
            'message': error.message,
            'position': error.position,
          },
        );

  void _revalidate() {
    final String trimmed = _text.trim();
    if (trimmed.isEmpty) {
      _syntaxError = null;
      _categoryHints = const <String>[];
      _commit(null);
      return;
    }
    final PExpr expr;
    try {
      expr = parsePreviewExpr(
        component.showCondition
            ? previewShowExpression(trimmed)! as String
            : trimmed,
      );
    } on PExprException catch (e) {
      // Held locally, never committed: a broken draft must not overwrite the
      // last value the document actually builds from.
      _syntaxError = _describe(e);
      return;
    }
    if (component.showCondition && isConstantExpr(expr)) {
      try {
        evalBool(expr, GlossShowScope());
      } on PExprException catch (error) {
        _syntaxError = _describe(error);
        return;
      }
    }
    _syntaxError = null;
    _categoryHints = _computeCategoryHints(expr);
    _commit(_encode(trimmed));
  }

  List<String> _computeCategoryHints(PExpr expr) {
    final List<String> categoryNames = component.categoryVariableNames;
    if (categoryNames.isEmpty) return const <String>[];
    final Set<String> published = categoryNames.toSet();
    final Set<String> hints = <String>{};
    previewCollectVarRefs(expr, (String name) {
      if (previewFlatCatalog.contains(name) && !published.contains(name)) {
        hints.add(name);
      }
    });
    return hints.toList()..sort();
  }

  void _commit(Object? next) {
    _lastCommitted = next;
    component.onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final List<String> completions = previewExprCompletions(
      _text,
      declaredVars: component.declaredVars,
      scope: component.scope,
    );
    final String listId = '$_id-completions';
    final String? syntaxError = _syntaxError?.call();
    return Field(
      label: component.label,
      forId: _id,
      required: component.required,
      tip: component.help,
      docKey: component.docKey,
      trailing: component.trailing,
      error: syntaxError,
      problems: <HuiIssue>[
        ...component.issues,
        for (final String name in _categoryHints)
          HuiIssue(
            severity: HuiSeverity.info,
            path: component.label,
            message:
                "{name} is not published while previewing this category; it reads as unresolved there.",
            messageArguments: <String, Object?>{'name': name},
          ),
      ],
      child: Component.fragment(<Widget>[
        TextInput(
          id: _id,
          value: _text,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          placeholder: component.placeholder,
          onChanged: _onInput,
          styles: technicalInputStyles,
          attributes: <String, String>{
            ...technicalInputAttributes,
            if (component.required) 'aria-required': 'true',
            if (syntaxError != null) 'aria-invalid': 'true',
            if (completions.isNotEmpty) 'list': listId,
          },
        ),
        if (completions.isNotEmpty)
          dom.datalist(id: listId, <Widget>[
            for (final String completion in completions)
              dom.option(value: completion, const <Widget>[]),
          ]),
      ]),
    );
  }
}
