package art.arcane.gloss.config.icon;

import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonObject;
import org.bukkit.entity.EntityType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class EntityIconDataTest {

  @Test
  public void namespacedLivingEntityRoundTrips() throws MenuIconException {
    EntityIconData data = entity(
        "{\"type\":\"entity\",\"entity\":\"minecraft:parrot\",\"width\":0.5,\"height\":0.9}");

    assertEquals(EntityType.PARROT, data.requireEntityType());
    assertEquals(0.5F, data.resolvedWidth(), 0F);
    assertEquals(0.9F, data.resolvedHeight(), 0F);

    JsonObject encoded = BukkitJson.GSON.toJsonTree(data, MenuIconData.class).getAsJsonObject();
    assertEquals("entity", encoded.get("type").getAsString());
    assertEquals("minecraft:parrot", encoded.get("entity").getAsString());
  }

  @Test
  public void missingDimensionsUseOneBlockDefaults() {
    EntityIconData data = entity("{\"type\":\"entity\",\"entity\":\"minecraft:cow\"}");

    assertNull(data.width());
    assertNull(data.height());
    assertEquals(1F, data.resolvedWidth(), 0F);
    assertEquals(1F, data.resolvedHeight(), 0F);
  }

  @Test
  public void omittedNamespaceResolvesAgainstMinecraft() throws MenuIconException {
    EntityIconData data = entity("{\"type\":\"entity\",\"entity\":\"parrot\"}");

    assertEquals(EntityType.PARROT, data.requireEntityType());
  }

  @Test
  public void unknownAndUnsafeTypesFailAtIconResolution() {
    EntityIconData unknown = entity("{\"type\":\"entity\",\"entity\":\"minecraft:not_real\"}");
    EntityIconData player = entity("{\"type\":\"entity\",\"entity\":\"minecraft:player\"}");
    EntityIconData item = entity("{\"type\":\"entity\",\"entity\":\"minecraft:item\"}");

    assertThrows(MenuIconException.class, unknown::requireEntityType);
    assertThrows(MenuIconException.class, player::requireEntityType);
    assertThrows(MenuIconException.class, item::requireEntityType);
  }

  @Test
  public void invalidDimensionsRejectTheDocument() {
    assertThrows(RuntimeException.class, () -> entity(
        "{\"type\":\"entity\",\"entity\":\"minecraft:parrot\",\"width\":0,\"height\":1}"));
    assertThrows(RuntimeException.class, () -> entity(
        "{\"type\":\"entity\",\"entity\":\"minecraft:parrot\",\"width\":1,\"height\":65}"));
  }

  private static EntityIconData entity(String json) {
    return (EntityIconData) BukkitJson.GSON.fromJson(json, MenuIconData.class);
  }
}
