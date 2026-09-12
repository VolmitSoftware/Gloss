package art.arcane.gloss.importer;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeatherBoardImporterTest {
  private static final Path FIXTURE = Path.of("src", "test", "resources", "importer", "featherboard");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void animatedTitlesAndLinesBecomeAnimationDocumentsTheBoardReferences() throws Exception {
    Path data = temp.newFolder("featherboard").toPath();
    DocumentImportPlan plan = service(data).preview(LegacyImportSource.FEATHERBOARD);

    assertTrue(plan.sourcePresent());
    assertEquals(List.of("animations/default-line-2.json", "animations/default-title.json",
            "boards/default.json"),
        plan.entries().stream().map(DocumentImportEntry::path).sorted().toList());
    DocumentImportEntry board = entry(plan, "boards/default.json");
    BoardDoc parsed = BoardDoc.parse("default.json", board.json());
    assertEquals("|animation.default-title|", parsed.presentation().title());
    assertEquals(List.of("&7Welcome back", "|animation.default-line-2|", "&8&m----------"),
        parsed.presentation().texts());
  }

  @Test
  public void aPerLineUpdateIntervalIsReportedBecauseABoardRefreshesAsOneDocument()
      throws Exception {
    Path data = temp.newFolder("intervals").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.FEATHERBOARD);

    assertTrue(entry(plan, "boards/default.json").warnings().toString(),
        entry(plan, "boards/default.json").warnings().stream()
            .anyMatch(warning -> warning.contains("own update interval")));
  }

  @Test
  public void theAnimationIntervalCarriesTheLegacyTickRateAsMilliseconds() throws Exception {
    Path data = temp.newFolder("interval-ms").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.FEATHERBOARD);

    AnimationDoc title = AnimationDoc.parse("default-title.json",
        entry(plan, "animations/default-title.json").json());
    assertEquals(500L, title.frameIntervalMs());
    assertEquals(List.of("&e&lSERVER", "&6&lSERVER"), title.frames());
  }

  @Test
  public void previewWritesNothingAndApplyRefusesToOverwriteWithoutTheFlag() throws Exception {
    Path data = temp.newFolder("apply").toPath();
    Files.createDirectories(data.resolve("boards"));
    Files.writeString(data.resolve("boards/default.json"),
        "{\"schemaVersion\":2,\"revision\":9}", StandardCharsets.UTF_8);
    DocumentImportService service = service(data);

    DocumentImportPlan plan = service.preview(LegacyImportSource.FEATHERBOARD);
    List<DocumentImportEntry> applied = service.apply(plan, false);

    assertEquals(1L, plan.conflictCount());
    assertEquals("{\"schemaVersion\":2,\"revision\":9}",
        Files.readString(data.resolve("boards/default.json")));
    assertTrue(applied.stream().noneMatch(entry -> entry.path().equals("boards/default.json")));
    assertTrue(Files.exists(data.resolve("animations/default-title.json")));
  }

  @Test
  public void applyWithTheOverwriteFlagReplacesTheDocumentAndKeepsTheOldCopy() throws Exception {
    Path data = temp.newFolder("overwrite").toPath();
    Files.createDirectories(data.resolve("boards"));
    Files.writeString(data.resolve("boards/default.json"),
        "{\"schemaVersion\":2,\"revision\":9}", StandardCharsets.UTF_8);
    List<String> recorded = new java.util.ArrayList<>();
    DocumentImportService service = new DocumentImportService(FIXTURE, data,
        new GlossProjectTransaction(data), new GlossPersistenceCoordinator(),
        (kind, id, content, source) -> recorded.add(kind + "/" + id + " " + source));

    service.apply(service.preview(LegacyImportSource.FEATHERBOARD), true);

    assertTrue(Files.readString(data.resolve("boards/default.json")).contains("animation.default-title"));
    assertEquals(List.of("boards/default import:featherboard"), recorded);
  }

  @Test
  public void applyHoldsThePersistenceWritePermitWhileItWrites() throws Exception {
    Path data = temp.newFolder("permit").toPath();
    Files.createDirectories(data.resolve("boards"));
    Files.writeString(data.resolve("boards/default.json"),
        "{\"schemaVersion\":2,\"revision\":9}", StandardCharsets.UTF_8);
    GlossPersistenceCoordinator coordinator = new GlossPersistenceCoordinator();
    java.util.concurrent.atomic.AtomicBoolean pausedDuringWrite =
        new java.util.concurrent.atomic.AtomicBoolean();
    DocumentImportService service = new DocumentImportService(FIXTURE, data,
        new GlossProjectTransaction(data), coordinator,
        (kind, id, content, source) -> pausedDuringWrite.set(coordinator.watcherPaused()));

    service.apply(service.preview(LegacyImportSource.FEATHERBOARD), true);

    assertTrue("the importer must write under the permit every other writer takes",
        pausedDuringWrite.get());
    assertFalse(coordinator.watcherPaused());
  }

  private static DocumentImportEntry entry(DocumentImportPlan plan, String path) {
    return plan.entries().stream()
        .filter(candidate -> candidate.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no entry for " + path + " in " + plan.entries()));
  }

  private DocumentImportService service(Path data) {
    return new DocumentImportService(FIXTURE, data, new GlossProjectTransaction(data),
        new GlossPersistenceCoordinator(),
        (kind, id, content, source) -> {
        });
  }
}
