package art.arcane.gloss.editor.sync;

import art.arcane.gloss.board.BoardDoc;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSyncPresetTest {
    private static final String BOARD = "{\"schemaVersion\":2,\"revision\":7,\"preset\":\"compact\"}";

    @TempDir
    Path root;

    @Test
    void workspacePreservesTheBoardSourceAndResolvesItsPreset() throws Exception {
        prepare("First");
        EditorSyncProject project = builder().open(1024 * 1024);
        assertEquals(BOARD, EditorSyncDocuments.parse(project.json()).stream()
            .filter(entry -> entry.kind().equals("scoreboard")).findFirst().orElseThrow().json());
        EditorSyncPublicationValidator.ValidatedProject validated = new EditorSyncPublicationValidator()
            .validate(session(project), new EditorSyncPublication(project.baseRevision(),
                project.json()), 1024 * 1024, project.json());
        assertEquals("First", board(validated).presentation().title());
        assertEquals(7L, board(validated).revision());
    }

    @Test
    void workspacePresetEditRecompilesValuesAndOnlyIncrementsCatalogRevision() throws Exception {
        prepare("First");
        EditorSyncProject project = builder().open(1024 * 1024);
        EditorSyncPublicationValidator.Base session = session(project);
        JsonObject changed = project.json().deepCopy();
        for (JsonElement element : changed.getAsJsonArray("documents")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("kind").getAsString().equals("presets")) {
                entry.addProperty("json", catalog("Second"));
            }
        }
        EditorSyncTestProjects.sign(changed);
        EditorSyncPublicationValidator.ValidatedProject validated = new EditorSyncPublicationValidator()
            .validate(session, new EditorSyncPublication(session.baseRevision(), changed),
                1024 * 1024, project.json());
        assertEquals("Second", board(validated).presentation().title());
        assertEquals(7L, board(validated).revision());
        assertEquals(2L, validated.appliedDocuments().get(
            new EditorSyncPublicationValidator.DocumentKey(EditorSyncDocumentKind.PRESETS, "presets"))
            .entry().revision());
        assertEquals(BOARD, validated.appliedDocuments().get(
            new EditorSyncPublicationValidator.DocumentKey(EditorSyncDocumentKind.SCOREBOARD, "test"))
            .entry().json());
    }

    private BoardDoc board(EditorSyncPublicationValidator.ValidatedProject project) {
        return (BoardDoc) project.appliedDocuments().get(
            new EditorSyncPublicationValidator.DocumentKey(EditorSyncDocumentKind.SCOREBOARD, "test")).value();
    }

    private EditorSyncContentSnapshotBuilder builder() {
        return new EditorSyncContentSnapshotBuilder(root);
    }

    private EditorSyncPublicationValidator.Base session(EditorSyncProject project) {
        return EditorSyncPublicationValidator.Base.of(project);
    }

    private void prepare(String title) throws Exception {
        Files.createDirectories(root.resolve("boards"));
        Files.writeString(root.resolve("boards/test.json"), BOARD);
        Files.writeString(root.resolve("presets.json"), catalog(title));
    }

    private String catalog(String title) {
        return """
            {"schemaVersion":1,"revision":1,"presets":{"boards":{"compact":{"values":{"presentation":{"title":"%s"}}}}}}
            """.formatted(title);
    }
}
