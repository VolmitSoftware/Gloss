/// Leaderboards are a document kind the editor lists and edits; unknown keys
/// (a statistic's material, keys of a newer plugin) survive every edit.
library;

import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

void main() {
  test('the shipped default decodes and round-trips', () {
    final String source = File(
      '../src/main/resources/defaults/leaderboards/playtime.json',
    ).readAsStringSync();
    final GlossLeaderboardDoc doc = decodeGlossLeaderboardDoc(source);
    expect(doc.sourceType, 'statistic');
    expect(doc.sourceValue, 'PLAY_ONE_MINUTE');
    expect(doc.size, 10);
    expect(doc.reset, 'monthly');
    expect(doc.playersPerTick, 8);
    expect(jsonDecode(encodeGlossLeaderboardDoc(doc)), jsonDecode(source));
  });

  test('edits write single keys and keep the rest', () {
    final GlossLeaderboardDoc doc = decodeGlossLeaderboardDoc(
      '{"schemaVersion":1,"revision":3,"source":{"type":"statistic",'
      '"statistic":"MINE_BLOCK","material":"DIAMOND_ORE"},"future":{"x":1}}',
    );
    doc
      ..sourceValue = 'BREAK_ITEM'
      ..size = 25
      ..order = 'asc';
    final Map<String, Object?> json = doc.toJson();
    expect(json['revision'], 3);
    expect(json['future'], <String, Object?>{'x': 1});
    expect(json['source'], <String, Object?>{
      'type': 'statistic',
      'statistic': 'BREAK_ITEM',
      'material': 'DIAMOND_ORE',
    });
    expect(json['size'], 25);
    expect(json['order'], 'asc');
  });

  test(
    'the sync wire kind maps to the adapter and a missing source is refused',
    () {
      expect(
        DocumentTypeRegistry.byWireKind('leaderboard'),
        DocumentTypes.leaderboard,
      );
      expect(
        () => decodeGlossLeaderboardDoc('{"schemaVersion":1,"revision":1}'),
        throwsA(isA<HuiFormatException>()),
      );
    },
  );

  test('a new leaderboard opens in the store and validates', () {
    final EditorStore store = EditorStore(
      workspace: Workspace(autoLoad: false),
      autosaveDelay: const Duration(days: 1),
    );
    store.newGlossDocument(DocumentTypes.leaderboard);
    expect(store.docKind, WorkspaceDocKind.leaderboard);
    expect(store.glossDoc, isA<GlossLeaderboardDoc>());
    store.mutateGloss('source', (GlossDoc doc) {
      (doc as GlossLeaderboardDoc).sourceValue = '';
    });
    expect(
      DocumentTypes.leaderboard.validate(store).map((issue) => issue.path),
      contains(r'$.source'),
    );
    store.dispose();
  });
}
