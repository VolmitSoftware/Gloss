package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.SoundActionData;
import art.arcane.gloss.enums.MenuActionCommandSource;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SoundActionResolveTest {

  @Test
  public void anUnknownSoundKeyIsDroppedWithOneWarningNamingTheComponentAndTheKey() {
    List<LogRecord> logged = new ArrayList<>();
    List<MenuAction<?>> resolved = resolveCapturing(logged, new SoundActionData("ui.button.nonexistent", null, null, null, null));

    assertEquals("an unresolvable sound must not survive as a clickable action", 1, resolved.size());
    assertTrue(resolved.get(0) instanceof CommandMenuAction);
    assertEquals("exactly one warning must be logged for one bad key", 1, logged.size());
    assertTrue(logged.get(0).getMessage().contains("buy"));
    assertTrue(logged.get(0).getMessage().contains("ui.button.nonexistent"));
  }

  @Test
  public void aMalformedSoundKeyIsDroppedTheSameWayAsAnUnknownOne() {
    List<LogRecord> logged = new ArrayList<>();
    List<MenuAction<?>> resolved = resolveCapturing(logged, new SoundActionData("UI_BUTTON_CLICK", null, null, null, null));

    assertEquals(1, resolved.size());
    assertEquals(1, logged.size());
    assertTrue(logged.get(0).getMessage().contains("UI_BUTTON_CLICK"));
  }

  @Test
  public void theWarningNamesTheOwningMenu() {
    List<LogRecord> logged = new ArrayList<>();
    resolveCapturing(logged, new SoundActionData("not.a.sound", null, null, null, null));

    assertEquals(1, logged.size());
    assertTrue(logged.get(0).getMessage().contains("shop"));
  }

  @Test
  public void aMissingSoundKeyIsDroppedWithoutThrowing() {
    List<LogRecord> logged = new ArrayList<>();
    List<MenuAction<?>> resolved = resolveCapturing(logged, new SoundActionData(null, null, null, null, null));

    assertEquals(1, resolved.size());
    assertEquals(1, logged.size());
  }

  @Test
  public void theSameInvalidActionWarnsOnlyOnceAcrossRepeatedSessionConstruction() {
    List<LogRecord> logged = new ArrayList<>();
    SoundActionData sound = new SoundActionData("ui.button.repeated_nonexistent", null, null, null, null);

    resolveCapturing(logged, sound);
    resolveCapturing(logged, sound);

    assertEquals(1, logged.size());
  }

  private static List<MenuAction<?>> resolveCapturing(List<LogRecord> logged, SoundActionData sound) {
    List<MenuActionData> declared = List.of(new CommandActionData(MenuActionCommandSource.PLAYER, "/spawn", null), sound);
    Logger logger = Logger.getLogger("Gloss");
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getLevel() == Level.WARNING)
          logged.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };

    logger.addHandler(handler);
    try {
      return MenuAction.resolve(declared, "shop", "buy");
    } finally {
      logger.removeHandler(handler);
    }
  }
}
