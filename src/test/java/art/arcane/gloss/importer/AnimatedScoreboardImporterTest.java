package art.arcane.gloss.importer;

import art.arcane.gloss.animation.AnimationDoc;
import art.arcane.gloss.board.BoardDoc;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnimatedScoreboardImporterTest {
  private static final Path FIXTURE =
      Path.of("src", "test", "resources", "importer", "animated-scoreboard");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void frameListsBecomeAnimationsAndStaticScoresStayLiteral() throws Exception {
    Path data = temp.newFolder("animated").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.ANIMATED_SCOREBOARD);

    BoardDoc board = BoardDoc.parse("main.json", entry(plan, "boards/main.json").json());
    assertEquals("|animation.main-title|", board.presentation().title());
    assertEquals(List.of("&7Rank: %vault_rank%", "|animation.main-line-2|"),
        board.presentation().texts());
    AnimationDoc title = AnimationDoc.parse("main-title.json",
        entry(plan, "animations/main-title.json").json());
    assertEquals(200L, title.frameIntervalMs());
  }

  @Test
  public void aPerScoreIntervalIsReported() throws Exception {
    Path data = temp.newFolder("score-interval").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.ANIMATED_SCOREBOARD);

    assertTrue(entry(plan, "boards/main.json").warnings().toString(),
        entry(plan, "boards/main.json").warnings().stream()
            .anyMatch(warning -> warning.contains("own interval")));
  }

  @Test
  public void theImportedBoardNeverSelectsItselfUntilAnOperatorSaysSo() throws Exception {
    Path data = temp.newFolder("selection").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.ANIMATED_SCOREBOARD);

    assertTrue(entry(plan, "boards/main.json").warnings().stream()
        .anyMatch(warning -> warning.contains("never selects itself")));
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
