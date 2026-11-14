library;

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import 'package:gloss_editor/logic/json_value.dart';
import 'package:gloss_editor/logic/preview_doc_validation.dart';
import 'package:gloss_editor/logic/validation.dart';
import 'package:gloss_editor/model/preview_doc.dart';
import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

void _installGerman(Map<String, String> messages) {
  huiLocalizations.installSnapshot(
    HuiLocaleSnapshot(
      locale: 'de_DE',
      messages: messages,
      contexts: const <String, String>{},
      plurals: const <String, Map<HuiPluralForm, String>>{},
      previewMessages: const <String, String>{},
    ),
  );
}

void main() {
  setUp(huiLocalizations.resetToEnglish);
  tearDown(huiLocalizations.resetToEnglish);

  test('JSON parse errors resolve in the active locale', () {
    final JsonParseResult result = parseJsonValue('{broken');
    expect(result.error, 'Not valid JSON (at character 2).');

    _installGerman(<String, String>{
      'Not valid JSON (at character {character}).':
          'Ungültiges JSON (bei Zeichen {character}).',
    });

    expect(result.error, 'Ungültiges JSON (bei Zeichen 2).');
  });

  test(
    'workspace errors resolve in the active locale after being stored',
    () async {
      final Workspace workspace = Workspace(
        repository: CallbackWorkspaceRepository(
          reader: (String _) => null,
          writer: (String _, String _) => true,
        ),
        autoLoad: false,
      );

      expect(await workspace.reset(), isFalse);
      expect(
        workspace.lastError,
        'This workspace repository cannot clear saved data.',
      );

      _installGerman(<String, String>{
        'This workspace repository cannot clear saved data.':
            'Dieses Arbeitsbereichs-Repository kann gespeicherte Daten nicht löschen.',
      });

      expect(
        workspace.lastError,
        'Dieses Arbeitsbereichs-Repository kann gespeicherte Daten nicht löschen.',
      );
    },
  );

  test('editor errors resolve in the active locale after being stored', () {
    final Workspace workspace = Workspace(
      read: (String _) => null,
      write: (String _, String _) => true,
      autoLoad: false,
    );
    final EditorStore store = EditorStore(
      workspace: workspace,
      autosaveDelay: Duration.zero,
    );

    store.importJson('broken.json', '{broken');
    expect(store.lastError, 'That file could not be read as JSON.');

    _installGerman(<String, String>{
      'That file could not be read as JSON.':
          'Diese Datei konnte nicht als JSON gelesen werden.',
    });

    expect(
      store.lastError,
      'Diese Datei konnte nicht als JSON gelesen werden.',
    );
    store.dispose();
  });

  test('adoption failures resolve in the active locale', () {
    final WorkspaceDoc document = WorkspaceDoc(
      id: '00000000-0000-4000-8000-000000000001',
      title: 'Broken',
      runtimeId: 'broken',
      json: '{broken',
      updatedAt: 1,
      folderId: null,
    );
    final AdoptedDocument adopted = DocumentTypes.menu.adopt(document);
    expect(adopted.failure, contains('was unreadable'));

    _installGerman(<String, String>{
      'The saved document "{title}" was unreadable ({error}) and was replaced with a new menu.':
          'Das gespeicherte Dokument "{title}" war unlesbar ({error}) und wurde durch ein neues Menü ersetzt.',
    });

    expect(adopted.failure, contains('war unlesbar'));
  });

  test('preview variable issues retain a stable localized template', () {
    final HuiPreviewDoc document = HuiPreviewDoc(
      elements: <HuiPreviewElement>[
        HuiPreviewElement('label', text: 'vars.missing'),
      ],
    );
    final HuiIssue issue = validatePreviewDoc(document).firstWhere(
      (HuiIssue candidate) => candidate.message.contains('Unknown variable'),
    );
    expect(issue.message, 'Unknown variable: vars.missing');

    _installGerman(<String, String>{
      'Unknown variable: {name}': 'Unbekannte Variable: {name}',
    });

    expect(issue.message, 'Unbekannte Variable: vars.missing');
  });

  test('server problems are worded in the active locale', () {
    expect(serverCopy('pairing-expired').cause, 'This link expired.');

    _installGerman(<String, String>{
      'This link expired.': 'Dieser Link ist abgelaufen.',
    });

    expect(serverCopy('pairing-expired').cause, 'Dieser Link ist abgelaufen.');
    expect(
      serverCopy('token-revoked').cause,
      'Pairing ended.',
      reason: 'a sentence with no translation stays English',
    );
  });
}
