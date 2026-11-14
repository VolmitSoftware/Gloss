import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/logic/document_presets.dart';
import 'package:test/test.dart';

import 'support/java_source.dart';
import 'support/sync_kinds.dart';

/// The file `EditorSyncDocumentKind.path` writes for [id], relative to the
/// plugin's data folder.
String _javaPath(ProtocolKind kind, String id) => kind.layout == 'SINGLE'
    ? kind.storageName
    : '${kind.storageName}/${kind.singletonId.isEmpty ? id : kind.singletonId}.json';

void main() {
  final List<ProtocolKind> javaKinds = protocolKinds(
    readGlossJava(
      'src/main/java/art/arcane/gloss/editor/sync/EditorSyncDocumentKind.java',
    ),
  );

  test('every exportable kind is a plugin kind', () {
    final Set<String> wireNames = <String>{
      for (final ProtocolKind kind in javaKinds) kind.wireName,
    };
    for (final DocumentTypeAdapter type in DocumentTypeRegistry.all) {
      if (!type.transferable) continue;
      expect(wireNames, contains(type.syncWireKind), reason: type.kind.name);
    }
  });

  test('data paths match the files the plugin writes', () {
    expect(javaKinds, isNotEmpty);
    for (final ProtocolKind kind in javaKinds) {
      final DocumentTypeAdapter? type = DocumentTypeRegistry.byWireKind(
        kind.wireName,
      );
      expect(type, isNotNull, reason: kind.wireName);
      final String id = switch (kind.wireName) {
        'strings' => 'en_US',
        'menu' || 'panel' => 'shops/main',
        _ => 'sample',
      };
      expect(type!.dataPath(id), _javaPath(kind, id), reason: kind.wireName);
    }
  });

  test('preset collections match the plugin collections', () {
    for (final ProtocolKind kind in javaKinds) {
      final String? expected = kind.wireName == 'presets'
          ? null
          : kind.storageName.replaceFirst(RegExp(r'\.json$'), '');
      expect(
        DocumentPresets.collection(kind.wireName),
        expected,
        reason: kind.wireName,
      );
      if (expected != null) {
        expect(DocumentPresets.kinds, contains(expected));
      }
    }
  });
}
