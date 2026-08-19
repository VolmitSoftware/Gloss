package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.icon.TextImageIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.gloss.util.common.math.CollisionPlane;
import com.google.common.collect.Lists;
import net.kyori.adventure.text.Component;
import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.ImageFormats;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class TextImageMenuIcon extends MenuIcon<TextImageIconData> {

  public static final List<Component> MISSING = Lists.newArrayList(
      TextUtils.textColor("████", "#000000").append(TextUtils.textColor("████", "#f800f8")),
      TextUtils.textColor("████", "#000000").append(TextUtils.textColor("████", "#f800f8")),
      TextUtils.textColor("████", "#000000").append(TextUtils.textColor("████", "#f800f8")),
      TextUtils.textColor("████", "#000000").append(TextUtils.textColor("████", "#f800f8")),
      TextUtils.textColor("████", "#f800f8").append(TextUtils.textColor("████", "#000000")),
      TextUtils.textColor("████", "#f800f8").append(TextUtils.textColor("████", "#000000")),
      TextUtils.textColor("████", "#f800f8").append(TextUtils.textColor("████", "#000000")),
      TextUtils.textColor("████", "#f800f8").append(TextUtils.textColor("████", "#000000")));
  private final List<Component> components;

  public TextImageMenuIcon(MenuSession session, Location loc, TextImageIconData data) throws MenuIconException {
    super(session, loc, data);
    components = createComponents();
  }

  public TextImageMenuIcon(MenuSession session, Location loc) throws MenuIconException {
    super(session, loc, null);
    components = MISSING;
  }

  @Override
  protected List<UUID> createDisplayEntities(Location loc) {
    List<UUID> uuids = Lists.newArrayList();
    Location lineLocation = session.getTransform().localPosition(
        loc,
        new Vector(0F, ((components.size() - 1) / 2F * localLineHeight()) - localLineHeight(), 0F)
    );
    components.forEach(c -> {
      uuids.add(DisplayEntityManager.add(textDisplay(c, lineLocation)));
      lineLocation.add(session.getTransform().localVector(new Vector(0F, -localLineHeight(), 0F)));
    });
    return uuids;
  }

  @Override
  public CollisionPlane createBoundingBox(Location anchor) {
    float lineHeight = scaledLineHeight();
    float characterWidth = scaledCharacterWidth();
    float width = 0;
    for (Component component : components)
      width = Math.max(width, TextUtils.content(component).length() * characterWidth / 2F);
    return session.getTransform().createPlane(textBoundingBoxCenter(anchor), width, components.size() * lineHeight);
  }

  private List<Component> createComponents() throws MenuIconException {
    String path = data.requirePath();
    try {
      Pair<ImageFormat, BufferedImage> imageData = Gloss.instance.getConfigManager().getImage(path);
      return TextImageRasterCache.lines(imageData.getRight(), imageData.getLeft() == ImageFormats.JPEG);
    } catch (IOException | RuntimeException e) {
      MenuIconException ex = new MenuIconException("Failed to load relative image \"%s\"!", path);
      ex.initCause(e);
      throw ex;
    }
  }
}
