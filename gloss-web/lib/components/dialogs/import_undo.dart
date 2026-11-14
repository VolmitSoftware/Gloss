/// Whether replacing the open document from the import dialog is one undo
/// step, so the dialog can say so and colour its button.
library;

/// Mirrors `EditorStore.importJson`: a menu over an open menu keeps the
/// history, and a fixed-file kind (motd.json, tablist.json and the like)
/// replaces its one existing document in place. Every other import starts
/// a new history, so Undo cannot bring the old document back.
bool importKeepsUndo({
  required bool incomingIsMenu,
  required bool incomingHasFixedFile,
  required bool openIsMenu,
  required bool fixedDocumentExists,
}) => incomingIsMenu ? openIsMenu : incomingHasFixedFile && fixedDocumentExists;
