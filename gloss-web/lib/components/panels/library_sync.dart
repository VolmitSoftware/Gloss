/// What a paired server's workspace changes in the library, kept free of
/// the DOM: renaming a server document renames it on the server (its file),
/// which Publish sends as the new id plus the removal of the old one.
library;

import '../../config/defaults.dart' show sanitizeMenuId;
import '../../doctype/doctype.dart';
import '../../l10n/hui_localizations.dart';
import '../../services/editor_sync.dart';
import '../../state/workspace.dart';

class LibrarySyncRules {
  const LibrarySyncRules(this.binding, this.workspace);

  /// The server's binding, or null in the browser workspace.
  final EditorSyncBinding? binding;
  final Workspace workspace;

  /// What the library does differently here, or null in the browser
  /// workspace.
  String? get scopeHint => binding == null
      ? null
      : huiText(
          'Server workspace: Publish sends only what changed here. Renaming a document renames its file on the server.',
        );

  /// What deleting [doc] does, for its confirm: a server document goes from
  /// the server too once published, with a copy kept in the server's
  /// history; anything else only leaves this browser.
  String deleteMessage(WorkspaceDoc doc) {
    final EditorSyncBinding? link = binding;
    final String what = link != null && link.isDocumentBound(doc)
        ? huiText(
            'Publish deletes it on the server too; the server history keeps a copy.',
          )
        : huiText('The document is deleted from this browser.');
    return '$what ${huiText('Undo cannot bring it back.')}';
  }

  /// The server id [doc] takes when the library renames it to [title]: the
  /// same folder, the title as the last part. Null when [doc] is not a
  /// server document, has a fixed id, or keeps its id.
  String? renamedRuntimeId(WorkspaceDoc doc, String title) {
    final EditorSyncBinding? link = binding;
    final String? current = doc.runtimeId;
    if (link == null || current == null || !link.isDocumentBound(doc)) {
      return null;
    }
    if (DocumentTypeRegistry.of(doc.kind).fixedRuntimeId != null) return null;
    final String wanted = title.trim().toLowerCase().replaceAll('/', '-');
    if (wanted.isEmpty) return null;
    final String leaf = sanitizeMenuId(wanted);
    final int slash = current.lastIndexOf('/');
    final String next = slash < 0
        ? leaf
        : '${current.substring(0, slash + 1)}$leaf';
    return next == current ? null : next;
  }
}
