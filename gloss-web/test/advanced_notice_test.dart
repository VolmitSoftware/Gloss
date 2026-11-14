/// What Simple says when documents arrive that only Advanced lists: how
/// many, that they are in Advanced, and which one Switch to Advanced opens.
library;

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/logic/advanced_notice.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

ArrivedDocument _doc(String id, WorkspaceDocKind kind) => (id: id, kind: kind);

final WorkspaceDocKind _names = DocumentTypes.names.kind;
final WorkspaceDocKind _glyph = DocumentTypes.glyph.kind;
final WorkspaceDocKind _flowMap = DocumentTypes.panel.kind;
final WorkspaceDocKind _menu = DocumentTypes.menu.kind;

void main() {
  group('advancedNotice', () {
    test('says nothing when Simple lists every document', () {
      expect(advancedNotice(Arrival.imported, <ArrivedDocument>[]), isNull);
      expect(
        advancedNotice(Arrival.imported, <ArrivedDocument>[_doc('a', _menu)]),
        isNull,
      );
      expect(
        advancedNotice(Arrival.server, <ArrivedDocument>[_doc('a', _menu)]),
        isNull,
      );
    });

    test('counts an import Simple hides entirely and lands on it', () {
      final AdvancedNotice one = advancedNotice(
        Arrival.imported,
        <ArrivedDocument>[_doc('names', _names)],
      )!;
      expect(one.message, '1 imported document is in Advanced.');
      expect(one.landing, 'names');

      final AdvancedNotice two = advancedNotice(
        Arrival.imported,
        <ArrivedDocument>[_doc('g', _glyph), _doc('f', _flowMap)],
      )!;
      expect(two.message, '2 imported documents are in Advanced.');
      expect(two.landing, 'g');
    });

    test('says how many of an import Simple hides', () {
      final AdvancedNotice notice = advancedNotice(
        Arrival.imported,
        <ArrivedDocument>[
          _doc('m', _menu),
          _doc('n', _names),
          _doc('h', _menu),
        ],
      )!;
      expect(notice.message, '1 of 3 imported documents is in Advanced.');
      expect(notice.landing, 'n');

      expect(
        advancedNotice(Arrival.imported, <ArrivedDocument>[
          _doc('m', _menu),
          _doc('n', _names),
          _doc('g', _glyph),
        ])!.message,
        '2 of 3 imported documents are in Advanced.',
      );
    });

    test('counts what the server brought that Simple hides', () {
      expect(
        advancedNotice(Arrival.server, <ArrivedDocument>[
          _doc('m', _menu),
          _doc('n', _names),
        ])!.message,
        '1 new document is in Advanced.',
      );
      expect(
        advancedNotice(Arrival.server, <ArrivedDocument>[
          _doc('n', _names),
          _doc('g', _glyph),
        ])!.message,
        '2 new documents are in Advanced.',
      );
    });
  });

  test('hiddenDocumentsText names the count', () {
    expect(hiddenDocumentsText(1), '1 document is in Advanced.');
    expect(hiddenDocumentsText(4), '4 documents are in Advanced.');
  });

  test('hiddenLanding is the newest document only Advanced lists', () {
    final List<WorkspaceDoc> docs = <WorkspaceDoc>[
      WorkspaceDoc(
        id: 'old',
        title: 'old',
        runtimeId: null,
        json: '{}',
        updatedAt: 1,
        folderId: null,
        kind: _names,
      ),
      WorkspaceDoc(
        id: 'menu',
        title: 'menu',
        runtimeId: null,
        json: '{}',
        updatedAt: 9,
        folderId: null,
        kind: _menu,
      ),
      WorkspaceDoc(
        id: 'new',
        title: 'new',
        runtimeId: null,
        json: '{}',
        updatedAt: 5,
        folderId: null,
        kind: _glyph,
      ),
    ];
    expect(hiddenLanding(docs), 'new');
    expect(hiddenLanding(<WorkspaceDoc>[docs[1]]), isNull);
  });

  group('ArrivalNotices', () {
    late DateTime now;
    late ArrivalNotices notices;

    setUp(() {
      now = DateTime(2026, 11, 14, 12);
      notices = ArrivalNotices(
        window: const Duration(seconds: 1),
        clock: () => now,
      );
    });

    test('counts files dropped together as one import', () {
      expect(
        notices.add(Arrival.imported, <ArrivedDocument>[_doc('m', _menu)]),
        isNull,
      );
      now = now.add(const Duration(milliseconds: 40));
      final AdvancedNotice notice = notices.add(
        Arrival.imported,
        <ArrivedDocument>[_doc('n', _names)],
      )!;
      expect(notice.message, '1 of 2 imported documents is in Advanced.');
      expect(notice.landing, 'n');
      now = now.add(const Duration(milliseconds: 40));
      expect(
        notices.add(Arrival.imported, <ArrivedDocument>[
          _doc('h', _menu),
        ])!.message,
        '1 of 3 imported documents is in Advanced.',
      );
    });

    test('counts a document written twice once', () {
      notices.add(Arrival.server, <ArrivedDocument>[_doc('n', _names)]);
      expect(
        notices.add(Arrival.server, <ArrivedDocument>[
          _doc('n', _names),
        ])!.message,
        '1 new document is in Advanced.',
      );
    });

    test('starts over after a pause or when the source changes', () {
      notices.add(Arrival.imported, <ArrivedDocument>[_doc('n', _names)]);
      now = now.add(const Duration(milliseconds: 1500));
      expect(
        notices.add(Arrival.imported, <ArrivedDocument>[
          _doc('g', _glyph),
        ])!.message,
        '1 imported document is in Advanced.',
      );
      expect(
        notices.add(Arrival.server, <ArrivedDocument>[
          _doc('s', _glyph),
        ])!.message,
        '1 new document is in Advanced.',
      );
    });
  });
}
