package art.arcane.gloss.editor.sync;

import art.arcane.gloss.board.BoardDoc;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSyncPresetTest {
    private static final String BOARD = "{\"schemaVersion\":2,\"revision\":7,\"preset\":\"compact\"}";

    @TempDir
    Path root;

    @Test
    void individualSessionPreservesSourceAndCarriesImmutablePresetContext() throws Exception {
        prepare("First");
        EditorSyncProject project = builder().open(EditorSyncKind.SCOREBOARD, "test", 1024 * 1024);
        assertEquals(BOARD, EditorSyncDocuments.parse(project.json()).getFirst().json());
        assertTrue(project.json().getAsJsonObject("constraints").has("presets"));
        EditorSyncPublicationValidator.ValidatedProject validated = new EditorSyncPublicationValidator()
            .validateBase(session(project), 1024 * 1024);
        assertEquals("First", board(validated).presentation().title());
        assertEquals(7L, board(validated).revision());
    }

    @Test
    void individualPublicationRefusesAChangedSharedPreset() throws Exception {
        prepare("First");
        EditorSyncProject project = builder().open(EditorSyncKind.SCOREBOARD, "test", 1024 * 1024);
        EditorSyncStoredSession session = session(project);
        prepare("Second");
        EditorSyncProject server = builder().open(EditorSyncKind.SCOREBOARD, "test", 1024 * 1024);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
            new EditorSyncPublicationValidator().validate(session,
                new EditorSyncPublication(1L, session.baseRevision(), project.json()), 1024 * 1024, server.json()));
        assertTrue(failure.getMessage().contains("Preset catalog changed"));
    }

    @Test
    void workspacePresetEditRecompilesValuesAndOnlyIncrementsCatalogRevision() throws Exception {
        prepare("First");
        EditorSyncProject project = builder().open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024);
        EditorSyncStoredSession session = session(project);
        JsonObject changed = project.json().deepCopy();
        for (JsonElement element : changed.getAsJsonArray("documents")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("kind").getAsString().equals("presets")) {
                entry.addProperty("json", catalog("Second"));
            }
        }
        EditorSyncTestProjects.sign(changed);
        EditorSyncPublicationValidator.ValidatedProject validated = new EditorSyncPublicationValidator()
            .validate(session, new EditorSyncPublication(1L, session.baseRevision(), changed),
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

    private EditorSyncStoredSession session(EditorSyncProject project) {
        return new EditorSyncStoredSession(new EditorSyncStoredSession.Capability(
            "session_id_123456789ab", "server_token_123456789", "https://relay.example/v3"),
            new EditorSyncStoredSession.Subject(project.kind(), project.subjectId()),
            Instant.now().plusSeconds(3600L), 0L, project.json(), null);
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
