package art.arcane.gloss.menu.icon;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.image.ImageAssets;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public class AnimatedTextImageMenuIcon extends MenuIcon<AnimatedImageData> {

  private final List<List<Component>> frameComponents = new ArrayList<>();

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
      uuids.add(DisplayEntityManager.add(session.displayGroup(), textDisplay(c, lineLocation)));
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

  private void createComponents() throws MenuIconException {
    try {
      List<ImageAssets.PreparedImage> frames = new ArrayList<>();
      boolean pending = false;
      int height = 0;
      for (String path : data.requireSource()) {
        Optional<ImageAssets.PreparedImage> prepared = Gloss.instance.getImageAssets().prepared(path);
        if (prepared.isEmpty()) {
          pending = true;
          continue;
        }
        ImageAssets.PreparedImage image = prepared.get();
        if (image.rows().isEmpty()) {
          TextImageRasterCache.reportOversize(path, image.width(), image.height());
          pending = true;
          continue;
        }
        frames.add(image);
        height = Math.max(height, image.height());
      }
      if (pending || frames.isEmpty()) {
        frameComponents.add(TextImageMenuIcon.MISSING);
        return;
      }
      for (ImageAssets.PreparedImage frame : frames) {
        if (frame.height() == height) {
          frameComponents.add(frame.rows());
          continue;
        }
        List<Component> lines = new ArrayList<>(frame.rows());
        Component empty = TextImageRasterCache.blankRow(frame.width());
        for (int row = frame.height(); row < height; row++) {
          lines.add(empty);
        }
        frameComponents.add(List.copyOf(lines));
      }
    } catch (IOException | RuntimeException failure) {
      MenuIconException rejected = new MenuIconException("Failed to construct animated icon!");
      rejected.initCause(failure);
      throw rejected;
    }
  }

  private void updateFrame() {
    List<Component> components = frameComponents.get(currentFrame);
    DisplayEntityManager.changeNames(displayEntities, components);
  }
}
