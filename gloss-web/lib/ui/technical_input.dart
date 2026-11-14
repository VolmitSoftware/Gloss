/// Attributes and styles for inputs that hold ids, paths, keys, expressions
/// and player names: left to right in every locale (a bidi island), no
/// autocomplete, no spellcheck. Pass both to the `TextInput`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart' show ArcaneStyleData, TextAlign;

const Map<String, String> technicalInputAttributes = <String, String>{
  'autocomplete': 'off',
  'spellcheck': 'false',
  'dir': 'ltr',
};

const ArcaneStyleData technicalInputStyles = ArcaneStyleData(
  textAlign: TextAlign.left,
);
