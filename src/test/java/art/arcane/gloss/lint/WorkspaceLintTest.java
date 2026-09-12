package art.arcane.gloss.lint;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorkspaceLintTest {
  private static final Path FIXTURES = Path.of("src", "test", "resources", "lint");

  @Test
  public void aNavigateActionPointingAtAMissingMenuIsAnError() throws IOException {
    Diagnostic diagnostic = only("dangling-navigate", NavigateTargetRule.CODE);

    assertEquals(Severity.ERROR, diagnostic.severity());
    assertEquals("menus", diagnostic.kind());
    assertEquals("root", diagnostic.id());
    assertEquals("/components/0/data/actions/0/target", diagnostic.pointer());
    assertEquals("dangling-navigate|menus|root|/components/0/data/actions/0/target|"
        + "navigates to a menu that does not exist: missing", diagnostic.wire());
  }

  @Test
  public void aPanelWhoseRootMenuIsGoneIsAnError() throws IOException {
    Diagnostic diagnostic = only("panel-root-missing", PanelRootRule.CODE);

    assertEquals(Severity.ERROR, diagnostic.severity());
    assertEquals("panels", diagnostic.kind());
    assertEquals("/rootMenuId", diagnostic.pointer());
  }

  @Test
  public void aTextImageWithoutItsFileIsAnError() throws IOException {
    Diagnostic diagnostic = only("image-missing", ImageRule.CODE);

    assertEquals("/components/0/data/icon/path", diagnostic.pointer());
    assertTrue(diagnostic.message(), diagnostic.message().contains("brand/logo.png"));
  }

  @Test
  public void anUnknownEmojiTokenIsAWarningOnlyWhereEmojiExist() throws IOException {
    Diagnostic diagnostic = only("emoji-unknown", EmojiRule.CODE);

    assertEquals(Severity.WARNING, diagnostic.severity());
    assertEquals("/components/0/data/icon/text", diagnostic.pointer());
    assertTrue(diagnostic.message(), diagnostic.message().contains("sparkle"));
  }

  @Test
  public void anUnknownAnimationTokenIsAnError() throws IOException {
    Diagnostic diagnostic = only("animation-unknown", AnimationRule.CODE);

    assertEquals(Severity.ERROR, diagnostic.severity());
    assertTrue(diagnostic.message(), diagnostic.message().contains("pulse"));
  }

  @Test
  public void aPlaceholderWhoseExpansionIsNotInstalledIsAWarning() throws IOException {
    LintContext context = LintContext.builder()
        .document("menus", "root", read("papi-expansion-missing", "menus/root.json"))
        .papiExpansions(Set.of("player"))
        .build();

    Diagnostic diagnostic = only(WorkspaceLint.run(context, Optional.empty(), Optional.empty()),
        PapiRule.CODE);

    assertEquals(Severity.WARNING, diagnostic.severity());
    assertTrue(diagnostic.message(), diagnostic.message().contains("vault"));
  }

  @Test
  public void aMetricNoPluginPublishesIsAWarning() throws IOException {
    LintContext context = LintContext.builder()
        .document("menus", "root", read("metric-unpublished", "menus/root.json"))
        .metricKeys(Set.of("gloss.boards-active"))
        .build();

    Diagnostic diagnostic = only(WorkspaceLint.run(context, Optional.empty(), Optional.empty()),
        MetricRule.CODE);

    assertTrue(diagnostic.message(), diagnostic.message().contains("react.tps"));
  }

  @Test
  public void aPermissionNoPluginDeclaresIsAWarning() throws IOException {
    LintContext context = LintContext.builder()
        .document("menus", "root", read("permission-unknown", "menus/root.json"))
        .permissions(Set.of("gloss.menu.open"))
        .build();

    Diagnostic diagnostic = only(WorkspaceLint.run(context, Optional.empty(), Optional.empty()),
        PermissionRule.CODE);

    assertEquals("/components/0/data/permission", diagnostic.pointer());
  }

  @Test
  public void twoVariantsAtTheSamePriorityAreAWarning() throws IOException {
    Diagnostic diagnostic = only("variant-priority-duplicate", VariantPriorityRule.CODE);

    assertEquals("/variants", diagnostic.pointer());
    assertTrue(diagnostic.message(), diagnostic.message().contains("priority 10"));
  }

  @Test
  public void aLiteralFalseConditionIsAWarning() throws IOException {
    Diagnostic diagnostic = only("condition-never-true", ConditionNeverTrueRule.CODE);

    assertEquals("/show", diagnostic.pointer());
  }

  @Test
  public void aViewerTokenOnTheServerListIsAWarning() throws IOException {
    Diagnostic diagnostic = only("viewer-token-on-viewerless-surface", ViewerTokenRule.CODE);

    assertEquals("motd", diagnostic.kind());
    assertEquals("/lines/0", diagnostic.pointer());
  }

  @Test
  public void aFileSkippedForItsSchemaIsCarriedThroughAsADiagnostic() throws IOException {
    LintContext context = LintContext.builder()
        .document("menus", "root", read("schema-skipped", "menus/root.json"))
        .skippedSchema("animations", "old", "declares an unsupported schemaVersion")
        .build();

    Diagnostic diagnostic = only(WorkspaceLint.run(context, Optional.empty(), Optional.empty()),
        SchemaSkippedRule.CODE);

    assertEquals("animations", diagnostic.kind());
    assertEquals("old", diagnostic.id());
  }

  @Test
  public void aPackOwnedDocumentRunningServerCommandsIsAWarning() throws IOException {
    Diagnostic diagnostic = only("server-command-in-pack", ServerCommandRule.CODE);

    assertEquals("menus", diagnostic.kind());
    assertEquals("shop", diagnostic.id());
    assertEquals("/components/0/data/actions/0", diagnostic.pointer());
  }

  @Test
  public void narrowingToOneKindAndIdDropsEverythingElse() throws IOException {
    LintContext context = LintContext.builder()
        .document("menus", "root", read("dangling-navigate", "menus/root.json"))
        .document("panels", "spawn", read("panel-root-missing", "panels/spawn.json"))
        .build();

    assertEquals(2, WorkspaceLint.run(context, Optional.empty(), Optional.empty()).size());
    assertEquals(1, WorkspaceLint.run(context, Optional.of("panels"), Optional.empty()).size());
    assertTrue(WorkspaceLint.run(context, Optional.of("panels"), Optional.of("other")).isEmpty());
  }

  private static Diagnostic only(String fixture, String code) throws IOException {
    LintContext context = fixture.equals("server-command-in-pack")
        ? packContext(fixture)
        : LintContext.fromDataDirectory(FIXTURES.resolve(fixture));
    return only(WorkspaceLint.run(context, Optional.empty(), Optional.empty()), code);
  }

  private static LintContext packContext(String fixture) throws IOException {
    return LintContext.builder()
        .document("menus", "shop", read(fixture, "menus/shop.json"))
        .packOwned(List.of("menus/shop.json"))
        .build();
  }

  private static Diagnostic only(List<Diagnostic> diagnostics, String code) {
    List<Diagnostic> matched = diagnostics.stream()
        .filter(diagnostic -> diagnostic.code().equals(code))
        .toList();
    assertEquals(diagnostics.toString(), 1, matched.size());
    return matched.getFirst();
  }

  private static String read(String fixture, String relative) throws IOException {
    return java.nio.file.Files.readString(FIXTURES.resolve(fixture).resolve(relative));
  }
}
