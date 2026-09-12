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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlossPackRemoveTest {
  private static final String EDITED = """
      {"schemaVersion":2,"revision":1,"title":"&aMine","lines":["&7mine"]}""";
  private static final String UPDATED = """
      {"schemaVersion":2,"revision":1,"title":"&aLobby v2","lines":["&7two"]}""";

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void removingDeletesOnlyTheFilesTheOperatorNeverEdited() throws Exception {
    Path data = temp.newFolder("remove").toPath();
    GlossPackInstaller installer = installer(data);
    installer.install(archive(Map.of("boards/lobby.json", GlossPackFixtures.BOARD,
        "boards/spawn.json", GlossPackFixtures.BOARD)), "file:pack");
    Files.writeString(data.resolve("boards/spawn.json"), EDITED, StandardCharsets.UTF_8);

    List<GlossPackPreview.Outcome> outcomes = installer.remove("lobby-starter");

    assertTrue(Files.notExists(data.resolve("boards/lobby.json")));
    assertEquals(EDITED, Files.readString(data.resolve("boards/spawn.json")).strip());
    assertTrue(outcomes.stream().anyMatch(outcome ->
        outcome.disposition() == GlossPackPreview.Disposition.KEEP_MODIFIED
            && outcome.path().equals("boards/spawn.json")));
    assertFalse(Files.exists(data.resolve("packs/lobby-starter.json")));
  }

  @Test
  public void removingTakesTheSidecarsAnUpdateLeftBesideAnEditedDocument() throws Exception {
    Path data = temp.newFolder("sidecars").toPath();
    GlossPackInstaller installer = installer(data);
    installer.install(archive(Map.of("boards/spawn.json", GlossPackFixtures.BOARD)), "file:pack");
    Files.writeString(data.resolve("boards/spawn.json"), EDITED, StandardCharsets.UTF_8);
    installer.update(archive("1.1.0", Map.of("boards/spawn.json", UPDATED)), "file:pack");
    Path sidecar = data.resolve("boards/spawn.lobby-starter.pack-new.json");
    assertTrue(Files.exists(sidecar));

    installer.remove("lobby-starter");

    assertFalse("an unmodified sidecar goes with the pack that wrote it", Files.exists(sidecar));
    assertEquals(EDITED, Files.readString(data.resolve("boards/spawn.json")).strip());
  }

  @Test
  public void aSidecarTheOperatorEditedIsKeptLikeAnyOtherOverlay() throws Exception {
    Path data = temp.newFolder("adopted").toPath();
    GlossPackInstaller installer = installer(data);
    installer.install(archive(Map.of("boards/spawn.json", GlossPackFixtures.BOARD)), "file:pack");
    Files.writeString(data.resolve("boards/spawn.json"), EDITED, StandardCharsets.UTF_8);
    installer.update(archive("1.1.0", Map.of("boards/spawn.json", UPDATED)), "file:pack");
    Path sidecar = data.resolve("boards/spawn.lobby-starter.pack-new.json");
    Files.writeString(sidecar, EDITED, StandardCharsets.UTF_8);

    installer.remove("lobby-starter");

    assertEquals(EDITED, Files.readString(sidecar).strip());
  }

  @Test
  public void removingAPackThatIsNotInstalledIsRefused() throws Exception {
    Path data = temp.newFolder("missing").toPath();

    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> installer(data).remove("lobby-starter"));
  }

  private GlossPackArchive archive(Map<String, String> documents) throws Exception {
    return archive("1.0.0", documents);
  }

  private GlossPackArchive archive(String version, Map<String, String> documents) throws Exception {
    return GlossPackArchive.read(GlossPackFixtures.archive(
        GlossPackFixtures.manifest("lobby-starter", version, documents, false),
        GlossPackFixtures.documents(documents)));
  }

  private GlossPackInstaller installer(Path data) {
    return new GlossPackInstaller(data, new GlossProjectTransaction(data),
        new GlossPackEnvironment("3.1.0", Set.of(), false), (kind, id, content, source) -> {
        });
  }
}
