/// What the inspector's preset picker offers for the active document.
library;

import 'dart:convert';

import '../doctype/document_type_registry.dart';
import '../state/editor_store.dart';
import '../state/workspace.dart';
import 'document_presets.dart';

final class DocumentPresetChoice {
  const DocumentPresetChoice({
    required this.selected,
    required this.names,
    required this.document,
  }) : error = null;

  const DocumentPresetChoice.unreadable(String this.error)
    : selected = '',
      names = const <String>[],
      document = const <String, Object?>{};

  /// The document's `preset`; empty means the collection defaults only.
  final String selected;

  /// The collection's named presets, sorted, plus [selected] when the
  /// catalog no longer defines it.
  final List<String> names;

  /// Why the catalog or the document could not be read.
  final String? error;

  /// The decoded document JSON.
  final Map<String, Object?> document;

  /// The document JSON with only its `preset` key changed; empty clears it.
  String withPreset(String name) {
    final Map<String, Object?> next = Map<String, Object?>.of(document);
    if (name.isEmpty) {
      next.remove('preset');
    } else {
      next['preset'] = name;
    }
    return jsonEncode(next);
  }
}

/// Null when there is nothing to pick: the kind has no preset collection,
/// it cannot be exported (flow maps are editor-only), or the workspace has
/// no catalog and the document names no preset.
DocumentPresetChoice? documentPresetChoice(EditorStore store) {
  if (!store.canTransferDocument) return null;
  final String? collection = DocumentPresets.collection(
    store.docType.syncWireKind,
  );
  if (collection == null) return null;
  String? catalogSource = store.workspace.active?.presetContext;
  if (catalogSource == null) {
    for (final WorkspaceDoc document in store.workspace.docs) {
      if (DocumentTypeRegistry.of(document.kind).syncWireKind == 'presets') {
        catalogSource = document.json;
        break;
      }
    }
  }
  final String exported;
  try {
    exported = store.exportJson();
  } on StateError {
    return null;
  }
  try {
    final Object? decoded = jsonDecode(exported);
    if (decoded is! Map<String, Object?>) return null;
    final Object? preset = decoded['preset'];
    final String selected = preset is String ? preset : '';
    if (catalogSource == null && selected.isEmpty) return null;
    final Set<String> names = <String>{};
    if (catalogSource != null) {
      final Object? catalog = jsonDecode(catalogSource);
      if (catalog is Map<String, Object?>) {
        final Object? presets = catalog['presets'];
        if (presets is Map<String, Object?>) {
          final Object? definitions = presets[collection];
          if (definitions is Map<String, Object?>) names.addAll(definitions.keys);
        }
      }
    }
    if (selected.isNotEmpty) names.add(selected);
    return DocumentPresetChoice(
      selected: selected,
      names: names.toList()..sort(),
      document: decoded,
    );
  } on FormatException catch (error) {
    return DocumentPresetChoice.unreadable(error.message);
  }
}
