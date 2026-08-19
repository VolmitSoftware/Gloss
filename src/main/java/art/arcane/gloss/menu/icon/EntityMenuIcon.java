package art.arcane.gloss.menu.icon;

import art.arcane.gloss.config.icon.EntityIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.math.CollisionPlane;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;

public final class EntityMenuIcon extends MenuIcon<EntityIconData> {
  private final EntityType entityType;
  private final float width;
  private final float height;

  public EntityMenuIcon(MenuSession session, Location loc, EntityIconData data) throws MenuIconException {
    super(session, loc, data);
    String entityKey = data.requireEntityType().getKey().toString();
    EntityType resolvedType;
    try {
      resolvedType = EntityTypes.getByName(entityKey);
    } catch (RuntimeException failure) {
      MenuIconException exception = new MenuIconException(
          "Unable to resolve packet entity type \"%s\"", entityKey);
      exception.initCause(failure);
      throw exception;
    }
    if (resolvedType == null) {
      throw new MenuIconException(
          "Entity type \"%s\" is unavailable on this server version", entityKey);
    }
    this.entityType = resolvedType;
    this.width = data.resolvedWidth();
    this.height = data.resolvedHeight();
  }

  @Override
  protected List<UUID> createDisplayEntities(Location loc) {
    DisplayEntity displayEntity = DisplayEntity.Builder.entity(entityType, position);
    return List.of(DisplayEntityManager.add(displayEntity));
  }

  @Override
  public CollisionPlane createBoundingBox(Location anchor) {
    float scale = uiScale();
    Vector center = session.getTransform().localPosition(
        anchor,
        new Vector(0F, height / 2F, 0F)
    ).toVector();
    return session.getTransform().createPlane(center, width * scale, height * scale);
  }
}
