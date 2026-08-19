package art.arcane.gloss.config.icon;

import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class BlockIconDataTest {

  @Test
  public void namespacedBlockMaterialRoundTrips() {
    BlockIconData data = block("{\"type\":\"block\",\"block\":\"minecraft:stone\"}");

    assertEquals(Material.STONE, data.blockType());
    JsonObject encoded = BukkitJson.GSON.toJsonTree(data, MenuIconData.class).getAsJsonObject();
    assertEquals("block", encoded.get("type").getAsString());
    assertEquals("minecraft:stone", encoded.get("block").getAsString());
  }

  @Test
  public void optionalStyleRoundTrips() {
    BlockIconData data = block("""
        {"type":"block","block":"minecraft:stone","style":{"scaleX":1.5,"billboard":"vertical"}}
        """);

    assertEquals(1.5F, data.style().scaleX(), 0F);
    assertEquals(IconBillboard.VERTICAL, data.style().billboard());
  }

  @Test
  public void unknownAndNonBlockMaterialsRemainDistinguishableForRuntimeValidation() {
    BlockIconData unknown = block("{\"type\":\"block\",\"block\":\"minecraft:not_real\"}");
    BlockIconData sword = block("{\"type\":\"block\",\"block\":\"minecraft:diamond_sword\"}");

    assertNull(unknown.blockType());
    assertThrows(MenuIconException.class, unknown::requireBlock);
    assertEquals(Material.DIAMOND_SWORD, sword.blockType());
  }

  private static BlockIconData block(String json) {
    return (BlockIconData) BukkitJson.GSON.fromJson(json, MenuIconData.class);
  }
}
