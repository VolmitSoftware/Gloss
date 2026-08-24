package art.arcane.gloss.editor.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class EditorSyncJsonTest {
  @Test
  public void canonicalHashMatchesTheCrossSurfaceV3Fixture() throws Exception {
    JsonObject fixture = fixture("/editor-sync-canonical-v3.json");
    JsonObject project = fixture.getAsJsonObject("project");
    String expected =
        "sha256:2385d7a78bd1d85df5f4ca2dae063a99efbead0d950d8a1d6122e7bab640d9fd";

    assertEquals(expected, project.get("baseRevision").getAsString());
    assertEquals(expected, EditorSyncJson.revision(project));
    JsonObject withoutRevision = project.deepCopy();
    withoutRevision.remove("baseRevision");
    assertEquals(fixture.get("canonicalWithoutBaseRevision").getAsString(),
        EditorSyncJson.canonical(withoutRevision));
    assertEquals(EditorSyncKind.WORKSPACE,
        EditorSyncProject.validated(project, 1024 * 1024).kind());
  }

  @Test
  public void canonicalNumbersUseFiniteIeee754ValuesAcrossRuntimes() {
    JsonObject probes = JsonParser.parseString("""
        {"integer":1,"decimalInteger":1.0,"negativeZero":-0.0,"fraction":0.1,
         "smallExponent":1e-7,"largeFixed":1e20}
        """).getAsJsonObject();

    assertEquals("{\"decimalInteger\":1,\"fraction\":0.1,\"integer\":1,"
        + "\"largeFixed\":100000000000000000000,\"negativeZero\":0,"
        + "\"smallExponent\":1e-7}", EditorSyncJson.canonical(probes));
  }

  @Test
  public void onlyTheRootBaseRevisionIsExcludedFromTheHash() {
    JsonObject project = JsonParser.parseString("""
        {"nested":{"baseRevision":"inner"},"baseRevision":"old"}
        """).getAsJsonObject();
    String first = EditorSyncJson.revision(project);
    project.addProperty("baseRevision", "changed-root");
    assertEquals(first, EditorSyncJson.revision(project));
    project.getAsJsonObject("nested").addProperty("baseRevision", "changed-inner");
    assertNotEquals(first, EditorSyncJson.revision(project));
  }

  @Test
  public void projectLimitsRejectDeepDocumentsBeforeDomainParsing() {
    JsonObject deep = new JsonObject();
    JsonObject current = deep;
    for (int index = 0; index < 70; index++) {
      JsonObject child = new JsonObject();
      current.add("child", child);
      current = child;
    }
    assertThrows(IllegalArgumentException.class,
        () -> EditorSyncJsonLimits.validate(deep));
  }

  private JsonObject fixture(String resourcePath) throws Exception {
    InputStream resource = getClass().getResourceAsStream(resourcePath);
    if (resource == null) {
      throw new IllegalStateException("missing editor sync canonical fixture: " + resourcePath);
    }
    try (InputStream input = resource;
         InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }
}
