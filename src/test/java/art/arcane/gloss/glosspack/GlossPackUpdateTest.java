package art.arcane.gloss.glosspack;

import art.arcane.gloss.persistence.GlossProjectTransaction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GlossPackUpdateTest {
  private static final String SECOND_BOARD = """
      {"schemaVersion":2,"revision":1,"title":"&aLobby v2","lines":["&7two"]}""";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void anUntouchedFileIsReplacedAndAnEditedOneIsKeptBesideTheNewVersion() throws Exception {
    Path data = temp.newFolder("update").toPath();
    GlossPackInstaller installer = installer(data);
    installer.install(archive("1.0.0",
        Map.of("boards/lobby.json", GlossPackFixtures.BOARD,
            "boards/spawn.json", GlossPackFixtures.BOARD)), "file:pack");
    Files.writeString(data.resolve("boards/spawn.json"), SECOND_BOARD, StandardCharsets.UTF_8);

    List<GlossPackPreview.Outcome> outcomes = installer.update(archive("1.1.0",
        Map.of("boards/lobby.json", SECOND_BOARD, "boards/spawn.json", SECOND_BOARD)),
        "file:pack");

    assertEquals(SECOND_BOARD, Files.readString(data.resolve("boards/lobby.json")).strip());
    assertEquals(SECOND_BOARD, Files.readString(data.resolve("boards/spawn.json")).strip());
    assertEquals(SECOND_BOARD,
        Files.readString(data.resolve("boards/spawn.lobby-starter.pack-new.json")).strip());
    assertEquals(Map.of("boards/lobby.json", GlossPackPreview.Disposition.UPDATE,
            "boards/spawn.json", GlossPackPreview.Disposition.KEEP_MODIFIED),
        dispositions(outcomes));
    assertEquals("1.1.0",
        new GlossPackLedgers(data).read("lobby-starter").manifest().packVersion());
  }

  @Test
  public void aFileTheNewVersionDropsIsRemovedWhenItWasNotEdited() throws Exception {
    Path data = temp.newFolder("dropped").toPath();
    GlossPackInstaller installer = installer(data);
    installer.install(archive("1.0.0",
        Map.of("boards/lobby.json", GlossPackFixtures.BOARD,
            "boards/old.json", GlossPackFixtures.BOARD)), "file:pack");

    installer.update(archive("1.1.0", Map.of("boards/lobby.json", GlossPackFixtures.BOARD)),
        "file:pack");

    assertTrue(Files.notExists(data.resolve("boards/old.json")));
    assertEquals(Set.of("boards/lobby.json"),
        new GlossPackLedgers(data).read("lobby-starter").installedHashes().keySet());
  }

  private static Map<String, GlossPackPreview.Disposition> dispositions(
      List<GlossPackPreview.Outcome> outcomes) {
    java.util.Map<String, GlossPackPreview.Disposition> byPath = new java.util.LinkedHashMap<>();
    for (GlossPackPreview.Outcome outcome : outcomes) {
      byPath.put(outcome.path(), outcome.disposition());
    }
    return byPath;
  }

  private GlossPackArchive archive(String packVersion, Map<String, String> documents)
      throws Exception {
    return GlossPackArchive.read(GlossPackFixtures.archive(
        GlossPackFixtures.manifest("lobby-starter", packVersion, documents, false),
        GlossPackFixtures.documents(documents)));
  }

  private GlossPackInstaller installer(Path data) {
    return new GlossPackInstaller(data, new GlossProjectTransaction(data),
        new GlossPackEnvironment("3.1.0", Set.of(), false), (kind, id, content, source) -> {
        });
  }
}
