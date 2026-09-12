package art.arcane.gloss.importer;

import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.tab.TablistDoc;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TabHeaderFooterImporterTest {
  private static final Path FIXTURE = Path.of("src", "test", "resources", "importer", "tab");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void theGlobalHeaderAndFooterJoinTheirLinesAndGroupsBecomeVariants() throws Exception {
    Path data = temp.newFolder("tab").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.TAB_HEADER_FOOTER);

    assertEquals(List.of("tablist/tablist.json"),
        plan.entries().stream().map(DocumentImportEntry::path).toList());
    TablistDoc tablist = TablistDoc.parse("tablist.json", plan.entries().getFirst().json());
    assertEquals("&bWelcome\n&7to the server", tablist.headerFooter().presentation().header());
    assertEquals("&8store.example.com", tablist.headerFooter().presentation().footer());
    assertEquals(1, tablist.headerFooter().variants().size());
    assertEquals("admin", tablist.headerFooter().variants().getFirst().id());
    assertEquals("inGroup('viewer', 'admin')",
        tablist.headerFooter().variants().getFirst().when());
    assertEquals("&cADMIN", tablist.headerFooter().variants().getFirst().presentation().header());
  }

  @Test
  public void perWorldOverridesAndListNamesAreReportedAsNotImported() throws Exception {
    Path data = temp.newFolder("not-imported").toPath();

    DocumentImportPlan plan = service(data).preview(LegacyImportSource.TAB_HEADER_FOOTER);

    List<String> warnings = plan.entries().getFirst().warnings();
    assertTrue(warnings.toString(),
        warnings.stream().anyMatch(warning -> warning.contains("per-world")));
    assertTrue(warnings.toString(),
        warnings.stream().anyMatch(warning -> warning.contains("listNames")));
  }

  @Test
  public void aServerWithoutTabReportsTheMissingSourceInsteadOfFailing() throws Exception {
    Path data = temp.newFolder("absent").toPath();
    DocumentImportService service = new DocumentImportService(data, data,
        new GlossProjectTransaction(data), new GlossPersistenceCoordinator(),
        (kind, id, content, source) -> {
        });

    DocumentImportPlan plan = service.preview(LegacyImportSource.TAB_HEADER_FOOTER);

    assertTrue(plan.entries().isEmpty());
    assertEquals(false, plan.sourcePresent());
  }

  private DocumentImportService service(Path data) {
    return new DocumentImportService(FIXTURE, data, new GlossProjectTransaction(data),
        new GlossPersistenceCoordinator(),
        (kind, id, content, source) -> {
        });
  }
}
