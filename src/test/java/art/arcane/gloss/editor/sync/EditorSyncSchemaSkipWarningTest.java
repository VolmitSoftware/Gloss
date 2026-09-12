package art.arcane.gloss.editor.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EditorSyncSchemaSkipWarningTest {
  private static final String SUPPORTED = """
      {"schemaVersion":1,"revision":1,"mode":"ascend","frameIntervalMs":1000,"frames":["a"]}
      """;
  private static final String FUTURE_SCHEMA = """
      {"schemaVersion":2,"revision":1,"mode":"ascend","frameIntervalMs":1000,"frames":["a"]}
      """;

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void aFileSkippedForItsSchemaVersionIsReportedInsteadOfDropped() throws Exception {
    Path data = temp.newFolder("skipped-schema").toPath();
    write(data.resolve("animations/current.json"), SUPPORTED);
    write(data.resolve("animations/future.json"), FUTURE_SCHEMA);

    EditorSyncProject project = new EditorSyncContentSnapshotBuilder(data)
        .open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024);

    assertEquals(List.of("current"),
        EditorSyncDocuments.parse(project.json()).stream()
            .map(EditorSyncDocuments.Entry::id).toList());
    assertEquals(List.of("schema-skipped|animation|future||declares an unsupported schemaVersion"),
        warnings(project));
  }

  @Test
  public void aSkippedSingletonIsReportedUnderItsSingletonId() throws Exception {
    Path data = temp.newFolder("skipped-singleton").toPath();
    write(data.resolve("tablist.json"), "{\"schemaVersion\":1,\"revision\":1}");

    EditorSyncProject project = new EditorSyncContentSnapshotBuilder(data)
        .open(EditorSyncKind.WORKSPACE, "workspace", 1024 * 1024);

    assertTrue(EditorSyncDocuments.parse(project.json()).isEmpty());
    assertEquals(List.of("schema-skipped|tablist|tablist||declares an unsupported schemaVersion"),
        warnings(project));
  }

  private static List<String> warnings(EditorSyncProject project) {
    JsonArray values = project.json().getAsJsonArray("warnings");
    List<String> warnings = new ArrayList<>(values.size());
    for (JsonElement value : values) {
      warnings.add(value.getAsString());
    }
    return List.copyOf(warnings);
  }

  private static void write(Path path, String source) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, source, StandardCharsets.UTF_8);
  }
}
