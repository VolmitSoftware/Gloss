package art.arcane.gloss.glosspack;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GlossPackManifestTest {
  @Test
  public void aWellFormedManifestParsesItsIdentityAndRequirements() {
    GlossPackManifest manifest = GlossPackManifest.parse(GlossPackFixtures.manifest(
        "lobby-starter", "1.2.0", Map.of("boards/lobby.json", GlossPackFixtures.BOARD), false));

    assertEquals("lobby-starter", manifest.id());
    assertEquals("1.2.0", manifest.packVersion());
    assertEquals(List.of("boards"),
        manifest.documents().stream().map(GlossPackManifest.DocumentRef::kind).toList());
    assertTrue(manifest.unmetRequirements(environment("3.1.0")).isEmpty());
  }

  @Test
  public void anIdThatIsNotASlugIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> GlossPackManifest.parse(
        GlossPackFixtures.manifest("Lobby Starter", "1.2.0", Map.of(), false)));
  }

  @Test
  public void aPackVersionThatIsNotSemverIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> GlossPackManifest.parse(
        GlossPackFixtures.manifest("lobby", "one", Map.of(), false)));
  }

  @Test
  public void aServerBelowTheRequiredGlossVersionIsReportedNotThrown() {
    GlossPackManifest manifest = GlossPackManifest.parse(
        GlossPackFixtures.manifest("lobby", "1.0.0", Map.of(), false));

    List<String> unmet = manifest.unmetRequirements(environment("2.9.9"));

    assertEquals(1, unmet.size());
    assertTrue(unmet.getFirst(), unmet.getFirst().contains(">=3.0.0"));
  }

  @Test
  public void aMissingRequiredPluginIsReported() {
    String body = GlossPackFixtures.manifest("lobby", "1.0.0", Map.of(), false)
        .replace("\"plugins\":[]", "\"plugins\":[\"PlaceholderAPI\"]");
    GlossPackManifest manifest = GlossPackManifest.parse(body);

    assertEquals(List.of("requires plugin PlaceholderAPI"),
        manifest.unmetRequirements(environment("3.1.0")));
  }

  @Test
  public void aForeignFormatOrVersionIsRefused() {
    String body = GlossPackFixtures.manifest("lobby", "1.0.0", Map.of(), false)
        .replace("\"version\":1", "\"version\":2");

    assertThrows(IllegalArgumentException.class, () -> GlossPackManifest.parse(body));
  }

  private static GlossPackEnvironment environment(String glossVersion) {
    return new GlossPackEnvironment(glossVersion, Set.of(), false);
  }
}
