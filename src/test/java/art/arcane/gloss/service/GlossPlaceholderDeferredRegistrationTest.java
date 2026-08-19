package art.arcane.gloss.service;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class GlossPlaceholderDeferredRegistrationTest {
  private static final Path PLUGIN_SOURCE = Path.of("src/main/java/art/arcane/gloss/Gloss.java");
  private static final Path PAPER_DESCRIPTOR = Path.of("src/main/resources/paper-plugin.yml");

  @Test
  public void theEnableTimeAttemptStillRunsSoSpigotOrderingKeepsWorking() throws Exception {
    String source = Files.readString(PLUGIN_SOURCE);
    assertTrue(
        "installPlaceholders must remain the enable-time starter for the placeholders service",
        source.contains("enableService(\"placeholders\", this::installPlaceholders, this::shutdownPlaceholders);"));
    assertTrue(
        "the enable-time path must attempt the install before arming the deferred watch",
        source.contains("if (tryInstallPlaceholders() || placeholderEnableListener != null) {"));
  }

  @Test
  public void aDeferredWatchInstallsTheExpansionWhenPlaceholderApiEnablesAfterGloss() throws Exception {
    String source = Files.readString(PLUGIN_SOURCE);
    assertTrue(
        "Gloss enables at STARTUP, so a PluginEnableEvent watch is the only way to catch a later PlaceholderAPI",
        source.contains("Events.listen(this, PluginEnableEvent.class, event -> {"));
    assertTrue(
        "the watch must only fire for PlaceholderAPI",
        source.contains("if (!PLACEHOLDER_API_PLUGIN.equals(event.getPlugin().getName())) {"));
    assertTrue(
        source.contains("private static final String PLACEHOLDER_API_PLUGIN = \"PlaceholderAPI\";"));
  }

  @Test
  public void theInstallIsIdempotentAndTheWatchIsDroppedOnSuccessAndOnShutdown() throws Exception {
    String source = Files.readString(PLUGIN_SOURCE);
    assertTrue(
        "an already-registered expansion must never be installed twice",
        source.contains("if (placeholderRegistration.isRegistered()) {\n            return true;\n        }"));
    assertTrue(
        "the watch must unregister itself once the expansion is installed",
        source.contains("if (tryInstallPlaceholders()) {\n                stopPlaceholderWatch();\n            }"));
    assertTrue(
        "shutdown must drop the watch as well as the expansion",
        source.contains("private void shutdownPlaceholders() {\n        stopPlaceholderWatch();\n        placeholderRegistration.unregister();\n    }"));
  }

  @Test
  public void thePaperDescriptorStillEnablesAtStartupSoTheDeferredPathIsLoadBearing() throws Exception {
    String descriptor = Files.readString(PAPER_DESCRIPTOR);
    assertTrue(
        "if this ever stops being STARTUP the deferred path is still harmless, but the fix exists because of it",
        descriptor.contains("load: STARTUP"));
  }
}
