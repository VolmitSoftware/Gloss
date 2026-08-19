package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;

public record ItemIconData(
    @SerializedName("item")
    @JsonAdapter(ItemIconData.MaterialAdapter.class)
    Material materialType,
    int count,
    int customModelValue,
    IconDisplayStyle style
) implements MenuIconData {
  public static ItemIconData of(ItemStack stack, boolean facing) {
    if (stack.hasItemMeta() && stack.getItemMeta().hasCustomModelData())
      return new ItemIconData(stack.getType(), stack.getAmount(), stack.getItemMeta().getCustomModelData(), null);
    else
      return new ItemIconData(stack.getType(), stack.getAmount(), 0, null);
  }

  public MenuIconType getType() {
    return MenuIconType.ITEM;
  }

  public Material requireMaterial() throws MenuIconException {
    if (materialType == null)
      throw new MenuIconException("Item icon has an unknown or invalid item id");
    return materialType;
  }

  static class MaterialAdapter extends TypeAdapter<Material> {
    @Override
    public void write(JsonWriter out, Material value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      out.value(value.getKey().toString());
    }

    @Override
    public Material read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      String raw = in.nextString();
      try {
        NamespacedKey key = NamespacedKey.fromString(raw);
        return key == null ? null : RegistryUtil.find(Material.class, key);
      } catch (RuntimeException | LinkageError e) {
        return null;
      }
    }
  }
}
