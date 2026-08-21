package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PlayerHeadIconDataTest {

  private static PlayerHeadIconData head(String json) {
    return (PlayerHeadIconData) BukkitJson.GSON.fromJson(json, MenuIconData.class);
  }

  @Test
  public void aLiteralNameRoundTrips() throws MenuIconException {
    PlayerHeadIconData data = head("{\"type\":\"playerHead\",\"player\":\"Notch\",\"refreshTicks\":40}");

    assertEquals(MenuIconType.PLAYER_HEAD, data.getType());
    assertEquals("Notch", data.requirePlayer());
    assertEquals(40, data.resolvedRefreshTicks());

    JsonObject encoded = BukkitJson.GSON.toJsonTree(data, MenuIconData.class).getAsJsonObject();
    assertEquals("playerHead", encoded.get("type").getAsString());
    assertEquals("Notch", encoded.get("player").getAsString());
  }

  @Test
  public void anOmittedRefreshUsesTheOneSecondDefault() {
    PlayerHeadIconData data = head("{\"type\":\"playerHead\",\"player\":\"%player_name%\"}");

    assertNull(data.refreshTicks());
    assertEquals(PlayerHeadIconData.DEFAULT_REFRESH_TICKS, data.resolvedRefreshTicks());
    assertEquals(20, PlayerHeadIconData.DEFAULT_REFRESH_TICKS);
  }

  @Test
  public void aPlayerHeadKeepsItsDisplayStyleUnlikeAnEntityIcon() {
    PlayerHeadIconData data = head(
        "{\"type\":\"playerHead\",\"player\":\"Notch\",\"style\":{\"billboard\":\"center\",\"scaleX\":2.0}}");

    assertEquals(IconBillboard.CENTER, IconDisplayStyle.resolve(data.style()).billboard());
    assertEquals(2F, IconDisplayStyle.resolve(data.style()).scaleX(), 0F);
  }

  @Test
  public void aBlankNameIsABrokenIconRatherThanAFallbackHead() {
    assertThrows(MenuIconException.class, () -> new PlayerHeadIconData(null, null, null).requirePlayer());
    assertThrows(MenuIconException.class, () -> new PlayerHeadIconData("   ", null, null).requirePlayer());
  }

  @Test
  public void authoredPaddingIsTrimmedOffTheName() throws MenuIconException {
    assertEquals("Notch", new PlayerHeadIconData("  Notch  ", null, null).requirePlayer());
  }

  @Test
  public void refreshTicksOutsideTheAcceptedRangeIsRejectedOnConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new PlayerHeadIconData("Notch", null, -1));
    assertThrows(IllegalArgumentException.class,
        () -> new PlayerHeadIconData("Notch", null, PlayerHeadIconData.MAX_REFRESH_TICKS + 1));
    assertEquals(0, new PlayerHeadIconData("Notch", null, 0).resolvedRefreshTicks());
    assertEquals(PlayerHeadIconData.MAX_REFRESH_TICKS,
        new PlayerHeadIconData("Notch", null, PlayerHeadIconData.MAX_REFRESH_TICKS).resolvedRefreshTicks());
  }

  @Test
  public void theTypeTagIsWhatTheEditorAndTheSchemaSpell() {
    assertEquals("playerHead", MenuIconType.PLAYER_HEAD.getSerializedName());
    assertEquals(PlayerHeadIconData.class, MenuIconType.PLAYER_HEAD.getType());
  }

  @Test
  public void anUnknownNameStillParsesSoTheMenuFileSurvives() throws MenuIconException {
    PlayerHeadIconData data = head("{\"type\":\"playerHead\",\"player\":\"this is not a username\"}");

    assertEquals("this is not a username", data.requirePlayer());
    assertFalse(art.arcane.gloss.profile.PlayerHeadService.isResolvableName(data.requirePlayer()));
    assertTrue(data instanceof MenuIconData);
  }
}
