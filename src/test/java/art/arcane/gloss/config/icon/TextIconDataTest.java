package art.arcane.gloss.config.icon;

import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class TextIconDataTest {

  @Test
  public void omittedRefreshUsesTheLiveDefault() {
    TextIconData data = text("{\"type\":\"text\",\"text\":\"%player_name%\"}");

    assertNull(data.refreshTicks());
    assertEquals(10, data.resolvedRefreshTicks());
  }

  @Test
  public void zeroDisablesAndMaximumIsAccepted() {
    assertEquals(0, text("{\"type\":\"text\",\"text\":\"A\",\"refreshTicks\":0}").resolvedRefreshTicks());
    assertEquals(1200, text("{\"type\":\"text\",\"text\":\"A\",\"refreshTicks\":1200}").resolvedRefreshTicks());
  }

  @Test
  public void valuesOutsideTheContractAreRejected() {
    assertThrows(RuntimeException.class,
        () -> text("{\"type\":\"text\",\"text\":\"A\",\"refreshTicks\":-1}"));
    assertThrows(RuntimeException.class,
        () -> text("{\"type\":\"text\",\"text\":\"A\",\"refreshTicks\":1201}"));
  }

  private static TextIconData text(String json) {
    return (TextIconData) BukkitJson.GSON.fromJson(json, MenuIconData.class);
  }
}
