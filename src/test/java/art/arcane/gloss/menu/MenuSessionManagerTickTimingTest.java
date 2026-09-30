package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MenuSessionManagerTickTimingTest {
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000071c4");
  private static final long HOLDER_WORK_NANOS = 3_000_000L;

  private Gloss previousGloss;

  @Before
  public void installGloss() throws ReflectiveOperationException {
    GlossTelemetry.clear();
    previousGloss = CharacterizationSupport.installGloss(
        CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of())));
  }

  @After
  public void restoreGloss() {
    CharacterizationSupport.restoreGloss(previousGloss);
    GlossTelemetry.clear();
  }

  @Test
  public void aHolderTickDeferredToTheRegionThreadRecordsItsWorkWhereItRuns() throws InterruptedException {
    MenuSessionManager manager = new MenuSessionManager();
    SessionHolder holder = new SessionHolder(slowPlayer(), new PlayerSnapshotStore<>());
    long start = 130_000L;
    GlossTelemetry.tickMsPerSecond(start);

    Thread regionThread = new Thread(() -> manager.tickHolder(holder));
    assertEquals("a holder tick that has only been queued has done no work yet",
        0D, GlossTelemetry.tickMsPerSecond(start + 1_000L), 0D);
    regionThread.start();
    regionThread.join();

    assertTrue("the holder's tick work must be measured on the thread that runs it",
        GlossTelemetry.tickMsPerSecond(start + 2_000L) >= HOLDER_WORK_NANOS / 1.0E6D);
  }

  private static Player slowPlayer() {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> PLAYER;
          case "isOnline" -> {
            spin(HOLDER_WORK_NANOS);
            yield true;
          }
          case "getName" -> "ticker";
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }

  private static void spin(long nanos) {
    long started = System.nanoTime();
    while (System.nanoTime() - started < nanos) {
      Thread.onSpinWait();
    }
  }
}
