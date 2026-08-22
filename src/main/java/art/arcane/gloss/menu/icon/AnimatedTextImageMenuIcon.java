package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.icon.AnimatedImageData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.gloss.util.common.math.CollisionPlane;
import com.google.common.collect.Lists;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class AnimatedTextImageMenuIcon extends MenuIcon<AnimatedImageData> {

  private final LinkedList<List<Component>> frameComponents = Lists.newLinkedList();

  private int currentFrame;
  private int passedTicks;

  public AnimatedTextImageMenuIcon(MenuSession session, Location loc, AnimatedImageData data) throws MenuIconException {
    super(session, loc, data);
    createComponents();
    currentFrame = passedTicks = 0;
  }

  @Override
  public void tick() {
    passedTicks++;
    if (passedTicks >= data.speed()) {
      passedTicks = 0;
      currentFrame = ++currentFrame % frameComponents.size();
      updateFrame();
    }
  }

  @Override
  protected List<UUID> createDisplayEntities(Location location) {
    List<UUID> uuids = Lists.newArrayList();
    Location lineLocation = session.getTransform().localPosition(
        location,
        new Vector(0F, ((frameComponents.getFirst().size() - 1) / 2F * localLineHeight()) - localLineHeight(), 0F)
    );
    frameComponents.getFirst().forEach(c -> {
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
    for (Component component : frameComponents.getFirst())
      width = Math.max(width, TextUtils.content(component).length() * characterWidth / 2F);
    return session.getTransform().createPlane(textBoundingBoxCenter(anchor), width, frameComponents.getFirst().size() * lineHeight);
  }

  private List<BufferedImage> getImages() throws IOException, MenuIconException {
    List<BufferedImage> images = Lists.newArrayList();
    for (String s : data.requireSource())
      images.add(Gloss.instance.getImageAssets().get(s).getRight());
    return images;
  }

  private void createComponents() throws MenuIconException {
    try {
      List<BufferedImage> images = getImages();
      int height = images
          .stream()
          .mapToInt(BufferedImage::getHeight)
          .max()
          .orElse(0);
      images.forEach(i -> {
        List<Component> raster = TextImageRasterCache.lines(i, false);
        int padding = height - i.getHeight();
        if (padding <= 0) {
          frameComponents.add(raster);
          return;
        }

        List<Component> lines = Lists.newArrayList(raster);
        Component empty = TextImageRasterCache.blankRow(i.getWidth());
        for (int y = 0; y < padding; y++) {
          lines.add(empty);
        }
        frameComponents.add(lines);
      });
    } catch (IOException | RuntimeException e) {
      MenuIconException ex = new MenuIconException("Failed to construct animated icon!");
      ex.initCause(e);
      throw ex;
    }
  }

  private void updateFrame() {
    List<Component> components = frameComponents.get(currentFrame);
    DisplayEntityManager.changeNames(displayEntities, components);
  }
}
