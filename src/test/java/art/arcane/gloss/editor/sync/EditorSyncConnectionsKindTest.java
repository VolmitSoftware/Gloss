package art.arcane.gloss.editor.sync;

import art.arcane.gloss.connection.ConnectionsDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EditorSyncConnectionsKindTest {
  private static final String BASE = "{\"schemaVersion\":1,\"revision\":1,"
      + "\"join\":{\"presentation\":{\"text\":\"&a+ {{ subject.name }}\"}},"
      + "\"leave\":{\"presentation\":{\"text\":\"&c- {{ subject.name }}\"}}}";
  private static final String EDITED = "{\"schemaVersion\":1,\"revision\":1,"
      + "\"join\":{\"audience\":\"server\",\"presentation\":{\"text\":\"&a+ {{ subject.name }}\"}},"
      + "\"leave\":{\"presentation\":{\"text\":\"&c- {{ subject.name }}\"}}}";
  private static final String UNKNOWN_AUDIENCE = "{\"schemaVersion\":1,\"revision\":1,"
      + "\"join\":{\"audience\":\"lobby\",\"presentation\":{\"text\":\"&a+ {{ subject.name }}\"}}}";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void theKindAddressesTheConnectionsSingletonTheSameWayMotdDoes() {
    assertEquals("connections", EditorSyncDocumentKind.CONNECTIONS.wireName());
    assertEquals("connections.json", EditorSyncDocumentKind.CONNECTIONS.storageName());
    assertEquals(EditorSyncDocumentKind.Layout.SINGLE,
        EditorSyncDocumentKind.CONNECTIONS.layout());
    assertEquals(ConnectionsDoc.KIND, EditorSyncDocumentKind.CONNECTIONS.singletonId());
    assertEquals(EditorSyncDocumentKind.CONNECTIONS,
        EditorSyncDocumentKind.byStorageCollection("connections.json"));
    assertTrue(EditorSyncDocumentKind.ORDERED_WIRE_NAMES.contains("connections"));
  }

  @Test
  public void theWorkspaceCatalogOffersTheShippedConnectionsDocumentAsATemplate() {
    JsonArray documents = EditorSyncServerCatalog.defaults().getAsJsonArray("connections");

    assertEquals(1, documents.size());
    JsonObject shipped = documents.get(0).getAsJsonObject();
    assertEquals("connections", shipped.get("id").getAsString());
    assertTrue(ConnectionsDoc.parse("connections.json", shipped.get("json").getAsString())
        .join().active());
  }

  @Test
  public void aWorkspaceSnapshotCarriesConnectionsJsonUnderItsSingletonId() throws Exception {
    Path data = temp.newFolder("connections-workspace").toPath();
    Files.writeString(data.resolve("connections.json"), BASE, StandardCharsets.UTF_8);
    EditorSyncContentSnapshotBuilder builder = new EditorSyncContentSnapshotBuilder(data);

    EditorSyncProject workspace = builder.open(1024 * 1024);
    List<EditorSyncDocuments.Entry> documents = EditorSyncDocuments.parse(workspace.json());

    assertEquals(1, documents.size());
    assertEquals("connections", documents.getFirst().kind());
    assertEquals("connections", documents.getFirst().id());
    assertEquals(Long.valueOf(1L), documents.getFirst().revision());
    assertEquals(BASE, documents.getFirst().json());
  }

  @Test
  public void aPublishedConnectionsDocumentIsAppliedAndWrittenToConnectionsJson() throws Exception {
    Path data = temp.newFolder("connections-publish").toPath();
    Files.writeString(data.resolve("connections.json"), BASE, StandardCharsets.UTF_8);
    JsonObject base = connectionsProject(BASE);
    EditorSyncPublicationValidator.Base session = session(base);

    EditorSyncPublicationValidator.ValidatedProject validated =
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(), connectionsProject(EDITED)),
            1024 * 1024, base);

    EditorSyncPublicationValidator.ParsedEntry applied = validated.appliedDocuments()
        .get(new EditorSyncPublicationValidator.DocumentKey(
            EditorSyncDocumentKind.CONNECTIONS, "connections"));
    assertEquals(Long.valueOf(2L), applied.entry().revision());

    Path target = EditorSyncDocumentKind.CONNECTIONS.path(data, "connections");
    assertEquals(data.resolve("connections.json"), target);
    Files.write(target, EditorSyncDocumentKind.CONNECTIONS
        .persistedBytes("connections", applied.entry().json()));

    ConnectionsDoc written = ConnectionsDoc.parse("connections.json", Files.readString(target));
    assertEquals(ConnectionsDoc.AUDIENCE_SERVER, written.join().audience());
    assertEquals(2L, written.revision());
    assertEquals(applied.entry().json(), EditorSyncDocuments.parse(
        new EditorSyncContentSnapshotBuilder(data)
            .open(1024 * 1024).json())
        .getFirst().json());
  }

  @Test
  public void anUnknownAudienceIsRefusedWithTheDocumentParserMessage() {
    JsonObject base = connectionsProject(BASE);
    EditorSyncPublicationValidator.Base session = session(base);

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
        new EditorSyncPublicationValidator().validate(session,
            new EditorSyncPublication(session.baseRevision(),
                connectionsProject(UNKNOWN_AUDIENCE)),
            1024 * 1024, base));

    assertEquals("connections.json connections audience must be one of [network, server]: lobby",
        failure.getMessage());
  }

  private static JsonObject connectionsProject(String source) {
    JsonObject document = EditorSyncTestProjects.document("connections", "connections", source);
    document.addProperty("revision", 1L);
    document.addProperty("baseRevision", EditorSyncDocuments.contentRevision(BASE));
    return EditorSyncTestProjects.workspaceProject(document);
  }

  private static EditorSyncPublicationValidator.Base session(JsonObject project) {
    return new EditorSyncPublicationValidator.Base(project);
  }
}
