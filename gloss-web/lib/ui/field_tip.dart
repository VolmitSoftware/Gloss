/// A field tip that leads with the field's own line and goes on with its
/// field doc, for a field whose line under the control moved behind the (i)
/// while the doc still says more.
library;

import '../config/field_docs.dart';

/// [lead], then the body of the field doc [docKey] when there is one.
String tipWithDoc(String lead, String? docKey) {
  final String? body = docKey == null ? null : huiFieldDoc(docKey)?.body.trim();
  return body == null || body.isEmpty ? lead : '$lead $body';
}
