/// What the code pane shows for a document's exported JSON.
///
/// A menu imported as one line keeps its bytes for export, so the pane would
/// show a single line thousands of characters long while every other kind
/// shows indented JSON. One-line JSON is shown through the export writer
/// instead (what Format would make of it); text that already has line breaks
/// is the author's layout and stays as it is.
library;

import 'dart:convert';

import '../../model/json_codec.dart';

String huiCodeViewText(String exported) {
  if (exported.contains('\n')) return exported;
  try {
    return huiWriteJson(jsonDecode(exported));
  } on FormatException {
    return exported;
  }
}
