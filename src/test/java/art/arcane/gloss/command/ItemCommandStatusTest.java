package art.arcane.gloss.command;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.integration.ProviderStatus;
import art.arcane.gloss.locale.GlossLocalization;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;

public class ItemCommandStatusTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void providerStatesSupplyOnlyTheArgumentsTheirMessagesDeclare() throws Exception {
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    GlossLocalization localization = new GlossLocalization(
        temporaryFolder.newFolder(), logger, GlossConfig.current().language());

    assertEquals("not installed", CommandGlossItem.stateText(
        localization,
        new ProviderStatus("missing", "Missing", false, false, false, 0)
    ));
    assertEquals("present, no adapter", CommandGlossItem.stateText(
        localization,
        new ProviderStatus("inactive", "Inactive", true, false, false, 0)
    ));
    assertEquals("present, still loading", CommandGlossItem.stateText(
        localization,
        new ProviderStatus("loading", "Loading", true, true, false, 0)
    ));
    assertEquals("ready, 12 ids", CommandGlossItem.stateText(
        localization,
        new ProviderStatus("ready", "Ready", true, true, true, 12)
    ));
  }
}
