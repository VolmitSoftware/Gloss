package art.arcane.gloss.importer;

import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.tab.TablistDoc;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CmiTabListImporterTest {
  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void headerBlocksBecomeAnAnimationAndGroupsBecomePermissionVariants() throws Exception {
    Path server = temp.newFolder("server").toPath();
    Path data = temp.newFolder("data").toPath();
    write(server, """
        TabList:
          GroupedEnabled: true
          addTabListHeader: true
          addTabListFooter: true
          Updates:
            AutoInterval: 2
          GeneralFormat:
            Header:
              '1':
              - '{#ff0000>}ab{#0000ff<}'
              - 'Online %server_online%/%server_max_players%'
              '2':
              - '{#gray}Hello [playerDisplayName]'
            Footer:
            - '&#00ff00Ping %player_ping%ms {#Fuchsia_Pink}x'
          GroupFormat:
            '5':
              Header:
              - '&cStaff'
        """);

    DocumentImportPlan plan = service(server, data).preview(LegacyImportSource.CMI_TABLIST);

    assertTrue(plan.sourcePresent());
    assertEquals(List.of("animations/cmi-tablist-header.json", "tablist/tablist.json"),
        plan.entries().stream().map(DocumentImportEntry::path).toList());
    JsonObject animation = JsonParser.parseString(plan.entries().getFirst().json()).getAsJsonObject();
    assertEquals(2000L, animation.get("frameIntervalMs").getAsLong());
    assertEquals("[ff0000]a[0000ff]b\nOnline {{ server.online }}/{{ server.maxPlayers }}",
        animation.getAsJsonArray("frames").get(0).getAsString());
    assertEquals("[aaaaaa]Hello {{ player.displayName }}",
        animation.getAsJsonArray("frames").get(1).getAsString());

    DocumentImportEntry entry = plan.entries().getLast();
    TablistDoc tablist = TablistDoc.parse("tablist.json", entry.json());
    assertEquals("|animation.cmi-tablist-header|", tablist.headerFooter().presentation().header());
    assertEquals("[00ff00]Ping {{ player.ping }}ms x", tablist.headerFooter().presentation().footer());
    assertEquals(1, tablist.headerFooter().variants().size());
    assertEquals("hasPermission('viewer', 'cmi.tablist.5')", tablist.headerFooter().variants().getFirst().when());
    assertEquals("&cStaff", JsonParser.parseString(entry.json()).getAsJsonObject()
        .getAsJsonObject("headerFooter").getAsJsonArray("variants").get(0).getAsJsonObject()
        .getAsJsonObject("presentation").get("header").getAsString());
    assertTrue(entry.warnings().toString(), entry.warnings().stream()
        .anyMatch(warning -> warning.contains("Fuchsia_Pink")));
    assertTrue(entry.warnings().toString(), entry.warnings().stream()
        .anyMatch(warning -> warning.contains("cmi.tablist.<number>")));
  }

  @Test
  public void disabledRefreshKeepsTheFirstBlockAndDisabledFooterStaysEmpty() throws Exception {
    Path server = temp.newFolder("static").toPath();
    Path data = temp.newFolder("static-data").toPath();
    write(server, """
        TabList:
          addTabListFooter: false
          Updates:
            AutoInterval: -1
          GeneralFormat:
            Header:
              '1': ['First']
              '2': ['Second']
            Footer: ['Ignored']
        """);

    DocumentImportPlan plan = service(server, data).preview(LegacyImportSource.CMI_TABLIST);

    assertEquals(1, plan.entries().size());
    TablistDoc tablist = TablistDoc.parse("tablist.json", plan.entries().getFirst().json());
    assertEquals("First", tablist.headerFooter().presentation().header());
    assertEquals("", tablist.headerFooter().presentation().footer());
    assertTrue(tablist.headerFooter().variants().isEmpty());
    assertTrue(plan.entries().getFirst().warnings().stream()
        .anyMatch(warning -> warning.contains("AutoInterval is off")));
  }

  @Test
  public void aServerWithoutCmiReportsTheMissingSource() throws Exception {
    Path server = temp.newFolder("absent").toPath();

    DocumentImportPlan plan = service(server, server).preview(LegacyImportSource.CMI_TABLIST);

    assertTrue(plan.entries().isEmpty());
    assertFalse(plan.sourcePresent());
  }

  private static void write(Path server, String yaml) throws Exception {
    Path file = server.resolve(CmiTabListScanner.RELATIVE_PATH);
    Files.createDirectories(file.getParent());
    Files.writeString(file, yaml);
  }

  private static DocumentImportService service(Path server, Path data) {
    return new DocumentImportService(server, data, new GlossProjectTransaction(data),
        new GlossPersistenceCoordinator(), (kind, id, content, source) -> {
        });
  }
}
