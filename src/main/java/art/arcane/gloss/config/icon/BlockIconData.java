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

import java.io.IOException;
import java.util.Locale;

public record BlockIconData(
    @SerializedName("block")
    @JsonAdapter(BlockIconData.MaterialAdapter.class)
    Material blockType,
    IconDisplayStyle style
) implements MenuIconData {
  @Override
  public MenuIconType getType() {
    return MenuIconType.BLOCK;
  }

  public Material requireBlock() throws MenuIconException {
    if (blockType == null) {
      throw new MenuIconException("Block icon has an unknown or invalid block id");
    }
    if (!blockType.isBlock()) {
      throw new MenuIconException("Block icon material \"%s\" is not a block", blockType.getKey());
    }
    return blockType;
  }

  static final class MaterialAdapter extends TypeAdapter<Material> {
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
      NamespacedKey key;
      try {
        key = NamespacedKey.fromString(raw);
      } catch (RuntimeException | LinkageError failure) {
        return null;
      }
      if (key == null) {
        return null;
      }
      try {
        return RegistryUtil.find(Material.class, key);
      } catch (RuntimeException | LinkageError unavailableRegistry) {
        return Material.getMaterial(key.getKey().toUpperCase(Locale.ROOT));
      }
    }
  }
}
