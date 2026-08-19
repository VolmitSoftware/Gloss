package art.arcane.gloss.config.icon;

import art.arcane.gloss.enums.MenuIconType;
import art.arcane.gloss.exceptions.MenuIconException;
import com.google.gson.annotations.SerializedName;

public record TextImageIconData(
    @SerializedName("path")
    String relativePath,
    IconDisplayStyle style
) implements MenuIconData {

  public MenuIconType getType() {
    return MenuIconType.TEXT_IMAGE;
  }

  public String requirePath() throws MenuIconException {
    if (relativePath == null || relativePath.isBlank())
      throw new MenuIconException("Image icon has no path");
    return relativePath;
  }
}
