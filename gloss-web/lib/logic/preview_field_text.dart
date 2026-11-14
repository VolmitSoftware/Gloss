/// Text rules of the container-preview inspector fields: the comma list that
/// edits `match.blocks`/`match.entities` (and their variant twins), and the
/// whole-text completions an expression field offers. DOM-free, so the VM
/// tests reach it.
library;

import 'preview_doc_validation.dart'
    show previewSuggestExprTokens, previewTrailingExprToken;

final RegExp _nameSeparator = RegExp(r'[\s,]+');

/// The names in [text]: split on commas and whitespace, upper-cased (Bukkit
/// enum names), blanks and repeats dropped, first spelling kept in order.
List<String> parsePreviewNameList(String text) {
  final Set<String> names = <String>{};
  for (final String part in text.split(_nameSeparator)) {
    final String name = part.trim().toUpperCase();
    if (name.isNotEmpty) names.add(name);
  }
  return names.toList();
}

/// [names] as the list field shows them.
String formatPreviewNameList(List<String> names) => names.join(', ');

/// [text] with its trailing identifier token replaced by [suggestion], or
/// with [suggestion] appended when the text ends in no token.
String previewCompleteExprToken(String text, String suggestion) {
  final RegExpMatch? match = previewTrailingExprToken.firstMatch(text);
  return match == null
      ? '$text$suggestion'
      : text.substring(0, match.start) + suggestion;
}

/// Every completion of [text]'s trailing token as the whole text it would
/// become ([previewSuggestExprTokens] decides which and when).
List<String> previewExprCompletions(
  String text, {
  required Set<String> declaredVars,
  required Set<String> scope,
}) => <String>[
  for (final String suggestion in previewSuggestExprTokens(
    text,
    declaredVars: declaredVars,
    scope: scope,
  ))
    previewCompleteExprToken(text, suggestion),
];
