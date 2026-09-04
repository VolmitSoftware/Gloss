package art.arcane.gloss.menu.icon;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.icon.AnimatedImageData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.config.icon.TextImageIconData;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.MenuSessionOptions;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.gloss.util.common.math.CollisionPlane;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IconFailureFallbackTest {

  private static Location anchor() {
    return new Location(null, 0D, 0D, 0D);
  }

  @Test
  public void textImageIconWithoutAUsablePathFailsAsAMenuIconException() {
    MenuSession session = session();

    assertThrows(MenuIconException.class, () -> new TextImageMenuIcon(session, anchor(), new TextImageIconData(null, null)));
    assertThrows(MenuIconException.class, () -> new TextImageMenuIcon(session, anchor(), new TextImageIconData("   ", null)));
  }

  @Test
  public void animatedIconWithoutUsableFramesFailsAsAMenuIconException() {
    MenuSession session = session();

    assertThrows(MenuIconException.class, () -> new AnimatedTextImageMenuIcon(session, anchor(), new AnimatedImageData(null, 2, null)));
    assertThrows(MenuIconException.class, () -> new AnimatedTextImageMenuIcon(session, anchor(), new AnimatedImageData(List.of(), 2, null)));
    assertThrows(MenuIconException.class, () -> new AnimatedTextImageMenuIcon(session, anchor(), new AnimatedImageData(Arrays.asList("frame0.png", null), 2, null)));
  }

  @Test
  public void animatedImageCadenceRejectsClientHeavyValues() {
    assertThrows(IllegalArgumentException.class,
        () -> new AnimatedImageData(List.of("frame0.png"), 1, null));
    assertThrows(IllegalArgumentException.class,
        () -> new AnimatedImageData(List.of("frame0.png"), 1201, null));
    assertEquals(2, new AnimatedImageData(List.of("frame0.png"), 2, null).speed());
  }

  @Test
  public void itemIconWithoutAResolvedMaterialFailsAsAMenuIconException() {
    assertThrows(MenuIconException.class, () -> new ItemIconData(null, 1, 0, null).requireMaterial());
  }

  @Test
  public void unknownAndBadlyCasedItemIdsStillParseSoTheMenuFileSurvives() {
    MenuIconData unknown = BukkitJson.GSON.fromJson("{\"type\":\"item\",\"item\":\"minecraft:not_a_real_item\"}", MenuIconData.class);
    MenuIconData badCase = BukkitJson.GSON.fromJson("{\"type\":\"item\",\"item\":\"DIAMOND_SWORD\"}", MenuIconData.class);

    assertTrue(unknown instanceof ItemIconData);
    assertTrue(badCase instanceof ItemIconData);
    assertNull(((ItemIconData) unknown).materialType());
    assertNull(((ItemIconData) badCase).materialType());
    assertThrows(MenuIconException.class, ((ItemIconData) unknown)::requireMaterial);
  }

  @Test
  public void missingIconKeepsItsEightRowCheckerboard() throws MenuIconException {
    assertEquals(8, TextImageMenuIcon.MISSING.size());
    TextImageMenuIcon.MISSING.forEach(row -> assertEquals(8, TextUtils.content(row).length()));
    TextImageMenuIcon icon = new TextImageMenuIcon(session(), anchor());
    CollisionPlane plane = icon.createBoundingBox(anchor());
    assertEquals(8F * MenuIcon.NAMETAG_SIZE, plane.getHeight(), 0F);
  }

  private static MenuSession session() {
    MenuDefinitionData data = new MenuDefinitionData(
        new Vector(),
        false,
        false,
        8D,
        false,
        false,
        List.<MenuComponentData>of(),
        List.of(), ShowCondition.ALWAYS
    );
    data.setId("icon-test");
    Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getLocation" -> anchor();
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Player[icon-test]";
          default -> throw new UnsupportedOperationException(method.getName());
        });
    return new MenuSession(data, player, MenuSessionOptions.personal(data, player, null));
  }
}
