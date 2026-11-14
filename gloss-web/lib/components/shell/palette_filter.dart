/// Search's filter: every word typed must appear somewhere in a command's
/// label, keywords or group, in any order and case. Pure, so it is unit
/// tested.
library;

/// The words of [query], lowercased; empty when nothing was typed.
List<String> paletteTerms(String query) => <String>[
  for (final String term in query.trim().toLowerCase().split(RegExp(r'\s+')))
    if (term.isNotEmpty) term,
];

/// Whether [haystack] (label, keywords and group) holds every term.
bool paletteMatches(String haystack, List<String> terms) {
  final String text = haystack.toLowerCase();
  return terms.every(text.contains);
}
