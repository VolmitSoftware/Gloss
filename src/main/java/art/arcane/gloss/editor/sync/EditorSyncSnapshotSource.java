package art.arcane.gloss.editor.sync;

interface EditorSyncSnapshotSource {
  EditorSyncProject open(EditorSyncKind kind, String subjectId, int maximumBytes);

  java.util.List<String> subjectIds(EditorSyncKind kind);
}
