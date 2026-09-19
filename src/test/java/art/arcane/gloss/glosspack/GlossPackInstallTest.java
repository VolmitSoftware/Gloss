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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GlossPackInstallTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void installingWritesTheDocumentsAndALedgerOfWhatItOwns() throws Exception {
    Path data = temp.newFolder("install").toPath();
    GlossPackInstaller installer = installer(data, false);
    GlossPackArchive archive = archive(Map.of("boards/lobby.json", GlossPackFixtures.BOARD));

    List<GlossPackPreview.Outcome> preview = installer.preview(archive);
    installer.install(archive, "file:lobby-starter.glosspack");

    assertEquals(List.of(GlossPackPreview.Disposition.CREATE),
        preview.stream().map(GlossPackPreview.Outcome::disposition).toList());
    assertEquals(GlossPackFixtures.BOARD,
        Files.readString(data.resolve("boards/lobby.json")).strip());
    GlossPackLedger ledger = new GlossPackLedgers(data).read("lobby-starter");
    assertEquals(Set.of("boards/lobby.json"), ledger.installedHashes().keySet());
    assertEquals("file:lobby-starter.glosspack", ledger.source());
  }

  @Test
  public void aPreviewChangesNothingOnDisk() throws Exception {
    Path data = temp.newFolder("dry-run").toPath();
    GlossPackInstaller installer = installer(data, false);

    installer.preview(archive(Map.of("boards/lobby.json", GlossPackFixtures.BOARD)));

    assertTrue(Files.notExists(data.resolve("boards/lobby.json")));
    assertTrue(Files.notExists(data.resolve("packs")));
  }

  @Test
  public void anIdAlreadyOwnedBySomethingElseRefusesTheWholeInstall() throws Exception {
    Path data = temp.newFolder("collision").toPath();
    Files.createDirectories(data.resolve("boards"));
    Files.writeString(data.resolve("boards/lobby.json"), GlossPackFixtures.BOARD);
    GlossPackInstaller installer = installer(data, false);
    GlossPackArchive archive = archive(Map.of("boards/lobby.json", GlossPackFixtures.BOARD));

    List<GlossPackPreview.Outcome> preview = installer.preview(archive);

    assertEquals(List.of(GlossPackPreview.Disposition.CONFLICT),
        preview.stream().map(GlossPackPreview.Outcome::disposition).toList());
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> installer.install(archive, "file:pack"));
    assertTrue(failure.getMessage(), failure.getMessage().contains("boards/lobby.json"));
  }

  @Test
  public void serverCommandActionsAreStrippedAndReportedWhenTheyAreNotAllowed() throws Exception {
    Path data = temp.newFolder("strip").toPath();
    GlossPackInstaller installer = installer(data, false);
    GlossPackArchive archive = archive(
        Map.of("menus/shop.json", GlossPackFixtures.MENU_WITH_SERVER_COMMAND));

    List<GlossPackPreview.Outcome> preview = installer.preview(archive);
    installer.install(archive, "file:pack");

    assertTrue(preview.stream().anyMatch(outcome ->
        outcome.disposition() == GlossPackPreview.Disposition.STRIP_SERVER_COMMAND));
    String installed = Files.readString(data.resolve("menus/shop.json"));
    assertFalse(installed, installed.contains("\"server\""));
    assertTrue(installed, installed.contains("spawn"));
  }

  @Test
  public void serverCommandActionsSurviveWhenBothSidesAllowThem() throws Exception {
    Path data = temp.newFolder("allowed").toPath();
    GlossPackInstaller installer = installer(data, true);
    GlossPackArchive archive = GlossPackArchive.read(GlossPackFixtures.archive(
        GlossPackFixtures.manifest("lobby-starter", "1.0.0",
            Map.of("menus/shop.json", GlossPackFixtures.MENU_WITH_SERVER_COMMAND), true),
        GlossPackFixtures.documents(
            Map.of("menus/shop.json", GlossPackFixtures.MENU_WITH_SERVER_COMMAND))));

    installer.install(archive, "file:pack");

    assertTrue(Files.readString(data.resolve("menus/shop.json")).contains("\"server\""));
  }

  @Test
  public void aDocumentKindThisServerDoesNotKnowIsReportedNotSilentlyDropped() throws Exception {
    Path data = temp.newFolder("unknown-kind").toPath();
    GlossPackInstaller installer = installer(data, false);
    GlossPackArchive archive = archive(Map.of("unsupported/sample.json", "{\"schemaVersion\":1}"));

    List<GlossPackPreview.Outcome> preview = installer.preview(archive);

    assertEquals(GlossPackPreview.Disposition.SKIP_REQUIREMENT,
        preview.getFirst().disposition());
    assertTrue(preview.getFirst().reason(), preview.getFirst().reason().contains("unsupported"));
  }

  @Test
  public void anArchiveEntryThatEscapesItsFolderIsRefused() throws Exception {
    byte[] bytes = GlossPackFixtures.archive(
        GlossPackFixtures.manifest("lobby-starter", "1.0.0", Map.of(), false),
        Map.of("documents/../../escape.json", "{}"));

    assertThrows(IllegalArgumentException.class, () -> GlossPackArchive.read(bytes));
  }

  private GlossPackArchive archive(Map<String, String> documents) throws Exception {
    return GlossPackArchive.read(GlossPackFixtures.archive(
        GlossPackFixtures.manifest("lobby-starter", "1.0.0", documents, false),
        GlossPackFixtures.documents(documents)));
  }

  private GlossPackInstaller installer(Path data, boolean allowServerCommands) {
    return new GlossPackInstaller(data, new GlossProjectTransaction(data),
        new GlossPackEnvironment("3.1.0", Set.of(), allowServerCommands),
        (kind, id, content, source) -> {
        });
  }
}
