import 'package:gloss_editor/components/dialogs/import_undo.dart';
import 'package:test/test.dart';

void main() {
  group('importKeepsUndo', () {
    test('should keep undo when a menu replaces an open menu', () {
      expect(
        importKeepsUndo(
          incomingIsMenu: true,
          incomingHasFixedFile: false,
          openIsMenu: true,
          fixedDocumentExists: false,
        ),
        isTrue,
      );
    });

    test('should lose undo when a menu replaces another kind', () {
      expect(
        importKeepsUndo(
          incomingIsMenu: true,
          incomingHasFixedFile: false,
          openIsMenu: false,
          fixedDocumentExists: false,
        ),
        isFalse,
      );
    });

    test('should keep undo when a fixed-file kind replaces its document', () {
      expect(
        importKeepsUndo(
          incomingIsMenu: false,
          incomingHasFixedFile: true,
          openIsMenu: false,
          fixedDocumentExists: true,
        ),
        isTrue,
      );
    });

    test('should lose undo when no fixed-file document exists yet', () {
      expect(
        importKeepsUndo(
          incomingIsMenu: false,
          incomingHasFixedFile: true,
          openIsMenu: true,
          fixedDocumentExists: false,
        ),
        isFalse,
      );
    });

    test('should lose undo for every other kind, open menu or not', () {
      for (final bool openIsMenu in <bool>[true, false]) {
        expect(
          importKeepsUndo(
            incomingIsMenu: false,
            incomingHasFixedFile: false,
            openIsMenu: openIsMenu,
            fixedDocumentExists: false,
          ),
          isFalse,
        );
      }
    });
  });
}
