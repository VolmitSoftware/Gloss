import 'dart:io';

import 'package:test/test.dart';

void main() {
  final String rail = File('lib/components/panels/editor_rail.dart')
      .readAsStringSync();
  final String dialog = File('lib/components/dialogs/new_document_dialog.dart')
      .readAsStringSync();
  final String choices = File('lib/components/dialogs/new_document_choice.dart')
      .readAsStringSync();
  final String appDialog = File('lib/ui/dialog.dart').readAsStringSync();
  final String modalFocus = File('lib/ui/modal_focus.dart').readAsStringSync();

  test('the library has one labelled New button and no other create row', () {
    final String header = _between(
      rail,
      'Widget _library(',
      'Widget _filterMenu(',
    );
    expect('RowAction('.allMatches(header).length, 1);
    expect(header, contains("huiText('New')"));
    expect(header, contains('id: NewDocumentDialog.triggerId'));
    expect(header, contains("'aria-label': huiText('New document')"));
    expect(header, contains("'aria-haspopup': 'dialog'"));
    expect(header, contains('expanded: component.newDocumentDialogOpen'));
    expect(header, contains("huiText('New folder')"));
    expect(rail, isNot(contains('createLabel')));
    expect(rail, isNot(contains('dom.button')));
    expect(rail, isNot(contains("'title': huiText(")));
  });

  test('templates and random start inside the New dialog', () {
    expect(
      File('lib/components/dialogs/templates_dialog.dart').existsSync(),
      isFalse,
    );
    expect(
      File('lib/components/dialogs/showcase_randomizer_dialog.dart')
          .existsSync(),
      isFalse,
    );
    expect(rail, isNot(contains('Randomizer')));
    expect(dialog, contains('NewDocumentStart.template'));
    expect(dialog, contains('NewDocumentStart.random'));
  });

  test('the New dialog lists kinds by group from the registry', () {
    expect(dialog, contains('newDocumentGroups('));
    expect(dialog, contains('kindInfo(type.kind).singular'));
    expect(dialog, isNot(contains('createLabel')));
    expect(choices, contains('groupsFor(advanced: advanced)'));
    expect(choices, contains('kindsIn(group, advanced: advanced)'));
    expect(choices, contains('DocumentTypeRegistry.of(info.kind)'));
    expect(
      _between(rail, 'void _openNewDocument()', 'void _createChoice('),
      contains('component.onNewDocument(_createChoice)'),
    );
  });

  test('focus moves into the New dialog and back to the trigger', () {
    expect(
      dialog,
      contains("static const String triggerId = 'hui-library-new-document';"),
    );
    expect(dialog, contains('AppDialog('));
    expect(dialog, contains('? NewDocumentDialog.firstChoiceId'));
    expect(
      _between(dialog, 'void didUpdateWidget(', 'void _focusFirstChoice()'),
      contains('_focusFirstChoice();'),
    );
    expect(appDialog, contains('with ModalFocus<AppDialog>'));
    expect(
      _between(modalFocus, 'void _closed()', 'back.focus()'),
      contains('_opener'),
    );
  });
}

String _between(String source, String start, String end) {
  final int startIndex = source.indexOf(start);
  final int endIndex = source.indexOf(end, startIndex + start.length);
  expect(startIndex, isNonNegative, reason: 'missing $start');
  expect(endIndex, greaterThan(startIndex), reason: 'missing $end');
  return source.substring(startIndex, endIndex);
}
