package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.icon.TextImageIconData;
import art.arcane.gloss.forge.GlyphAtlas;
import art.arcane.gloss.forge.GlyphRegistry;
import art.arcane.gloss.forge.PackNamespace;
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
import java.util.Optional;
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
  /** A block-space line is eight pixels tall, which is what one vanilla font line occupies. */
  private static final float PIXELS_PER_LINE = 8F;

  private final List<Component> components;
  private final float planeLines;

  public TextImageMenuIcon(MenuSession session, Location loc, TextImageIconData data) throws MenuIconException {
    super(session, loc, data);
    Optional<GlyphRegistry.ResolvedGlyph> glyph = PackNamespace.loaded(session.getPlayer())
        ? GlyphAtlas.lookup(data.requirePath()) : Optional.empty();
    if (glyph.isPresent()) {
      components = glyphLines(GlyphAtlas.glyphFor(data.requirePath()).orElseThrow());
      planeLines = glyphPlaneLines(glyph.get().height());
    } else {
      components = createComponents();
      planeLines = components.size();
    }
  }

  public TextImageMenuIcon(MenuSession session, Location loc) throws MenuIconException {
    super(session, loc, null);
    components = MISSING;
    planeLines = MISSING.size();
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
    return session.getTransform().createPlane(textBoundingBoxCenter(anchor), width, planeLines * lineHeight);
  }

  /** The glyph string as the single line a pack viewer sees. */
  static List<Component> glyphLines(String glyph) {
    return List.of(TextUtils.parse(glyph));
  }

  /** How many block-space lines a glyph of this pixel height occupies. */
  static float glyphPlaneLines(int heightPx) {
    return Math.max(1F, heightPx / PIXELS_PER_LINE);
  }

  private List<Component> createComponents() throws MenuIconException {
    String path = data.requirePath();
    BufferedImage image = null;
    try {
      Pair<ImageFormat, BufferedImage> imageData = Gloss.instance.getImageAssets().get(path);
      image = imageData.getRight();
      return TextImageRasterCache.lines(image, imageData.getLeft() == ImageFormats.JPEG);
    } catch (IOException | RuntimeException e) {
      if (image != null && (image.getWidth() > TextImageRasterCache.MAX_DIMENSION
          || image.getHeight() > TextImageRasterCache.MAX_DIMENSION)) {
        TextImageRasterCache.reportOversize(path, image.getWidth(), image.getHeight());
      }
      MenuIconException ex = new MenuIconException("Failed to load relative image \"%s\"!", path);
      ex.initCause(e);
      throw ex;
    }
  }
}
