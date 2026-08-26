package art.arcane.gloss.util.common;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.text.TextDisplayLayout;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitBlockData;
import static io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack;

public class DisplayEntity {
  private static final MetadataIndex[] ALL_METADATA_INDICES = MetadataIndex.values();
  private static final EnumSet<MetadataIndex> RAW_ENTITY_METADATA_INDICES =
      EnumSet.of(MetadataIndex.ENTITY_FLAGS, MetadataIndex.NO_GRAVITY);
  private static final String COLLISION_TEAM_PREFIX = "gls_nc_";
  private static final WrapperPlayServerTeams.ScoreBoardTeamInfo COLLISIONLESS_TEAM =
      new WrapperPlayServerTeams.ScoreBoardTeamInfo(
          Component.empty(),
          Component.empty(),
          Component.empty(),
          WrapperPlayServerTeams.NameTagVisibility.NEVER,
          WrapperPlayServerTeams.CollisionRule.NEVER,
          NamedTextColor.WHITE,
          WrapperPlayServerTeams.OptionData.NONE
      );

  private final int id;
  private final UUID uuid;
  private EntityType entityType;
  private DisplayKind displayKind = DisplayKind.RAW;
  private byte entityFlags = 0;
  private boolean noGravity = true;
  private Vector3d location = new Vector3d();
  private float pitch = 0f, yaw = 0f, headYaw = 0f;
  private int interpolationDelay = 0;
  private int interpolationDuration = 0;
  private int teleportDuration = 0;
  private Vector3f translation = new Vector3f(0, 0, 0);
  private Vector3f scale = new Vector3f(1, 1, 1);
  private Quaternion4f leftRotation = new Quaternion4f(0, 0, 0, 1);
  private Quaternion4f rightRotation = new Quaternion4f(0, 0, 0, 1);
  private byte billboard = 0;
  private int brightness = -1;
  private float viewRange = 1f;
  private float shadowRadius = 0f;
  private float shadowStrength = 0f;
  private float width = 0f;
  private float height = 0f;
  private int glowColorOverride = -1;
  private Component text = Component.empty();
  private int lineWidth = TextDisplayLayout.FULL_WIDTH;
  private int backgroundColor = 0;
  private byte textOpacity = (byte) 0xFF;
  private byte textFlags = 0;
  private ItemStack item;
  private byte itemDisplayType = 0;
  private int blockState = 0;

  public DisplayEntity(int id, UUID uuid, EntityType entityType) {
    this.id = id;
    this.uuid = uuid;
    this.entityType = entityType;
  }

  public int id() {
    return id;
  }

  public UUID uuid() {
    return uuid;
  }

  public EntityType entityType() {
    return entityType;
  }

  public DisplayEntity entityType(EntityType entityType) {
    this.entityType = entityType;
    return this;
  }

  public DisplayKind displayKind() {
    return displayKind;
  }

  public DisplayEntity displayKind(DisplayKind displayKind) {
    this.displayKind = displayKind;
    return this;
  }

  public byte entityFlags() {
    return entityFlags;
  }

  public DisplayEntity entityFlags(byte entityFlags) {
    this.entityFlags = entityFlags;
    return this;
  }

  public boolean noGravity() {
    return noGravity;
  }

  public DisplayEntity noGravity(boolean noGravity) {
    this.noGravity = noGravity;
    return this;
  }

  public Vector3d location() {
    return location;
  }

  public DisplayEntity location(Vector3d location) {
    this.location = location;
    return this;
  }

  public float pitch() {
    return pitch;
  }

  public DisplayEntity pitch(float pitch) {
    this.pitch = pitch;
    return this;
  }

  public float yaw() {
    return yaw;
  }

  public DisplayEntity yaw(float yaw) {
    this.yaw = yaw;
    return this;
  }

  public float headYaw() {
    return headYaw;
  }

  public DisplayEntity headYaw(float headYaw) {
    this.headYaw = headYaw;
    return this;
  }

  public int interpolationDelay() {
    return interpolationDelay;
  }

  public DisplayEntity interpolationDelay(int interpolationDelay) {
    this.interpolationDelay = interpolationDelay;
    return this;
  }

  public int interpolationDuration() {
    return interpolationDuration;
  }

  public DisplayEntity interpolationDuration(int interpolationDuration) {
    this.interpolationDuration = interpolationDuration;
    return this;
  }

  public int teleportDuration() {
    return teleportDuration;
  }

  public DisplayEntity teleportDuration(int teleportDuration) {
    this.teleportDuration = teleportDuration;
    return this;
  }

  public Vector3f translation() {
    return translation;
  }

  public DisplayEntity translation(Vector3f translation) {
    this.translation = translation;
    return this;
  }

  public Vector3f scale() {
    return scale;
  }

  public DisplayEntity scale(Vector3f scale) {
    this.scale = scale;
    return this;
  }

  public Quaternion4f leftRotation() {
    return leftRotation;
  }

  public DisplayEntity leftRotation(Quaternion4f leftRotation) {
    this.leftRotation = leftRotation;
    return this;
  }

  public Quaternion4f rightRotation() {
    return rightRotation;
  }

  public DisplayEntity rightRotation(Quaternion4f rightRotation) {
    this.rightRotation = rightRotation;
    return this;
  }

  public byte billboard() {
    return billboard;
  }

  public DisplayEntity billboard(byte billboard) {
    this.billboard = billboard;
    return this;
  }

  public int brightness() {
    return brightness;
  }

  public DisplayEntity brightness(int brightness) {
    this.brightness = brightness;
    return this;
  }

  public float viewRange() {
    return viewRange;
  }

  public DisplayEntity viewRange(float viewRange) {
    this.viewRange = viewRange;
    return this;
  }

  public float shadowRadius() {
    return shadowRadius;
  }

  public DisplayEntity shadowRadius(float shadowRadius) {
    this.shadowRadius = shadowRadius;
    return this;
  }

  public float shadowStrength() {
    return shadowStrength;
  }

  public DisplayEntity shadowStrength(float shadowStrength) {
    this.shadowStrength = shadowStrength;
    return this;
  }

  public float width() {
    return width;
  }

  public DisplayEntity width(float width) {
    this.width = width;
    return this;
  }

  public float height() {
    return height;
  }

  public DisplayEntity height(float height) {
    this.height = height;
    return this;
  }

  public int glowColorOverride() {
    return glowColorOverride;
  }

  public DisplayEntity glowColorOverride(int glowColorOverride) {
    this.glowColorOverride = glowColorOverride;
    return this;
  }

  public Component text() {
    return text;
  }

  public DisplayEntity text(Component text) {
    this.text = text;
    return this;
  }

  public int lineWidth() {
    return lineWidth;
  }

  public DisplayEntity lineWidth(int lineWidth) {
    this.lineWidth = lineWidth;
    return this;
  }

  public int backgroundColor() {
    return backgroundColor;
  }

  public DisplayEntity backgroundColor(int backgroundColor) {
    this.backgroundColor = backgroundColor;
    return this;
  }

  public byte textOpacity() {
    return textOpacity;
  }

  public DisplayEntity textOpacity(byte textOpacity) {
    this.textOpacity = textOpacity;
    return this;
  }

  public byte textFlags() {
    return textFlags;
  }

  public DisplayEntity textFlags(byte textFlags) {
    this.textFlags = textFlags;
    return this;
  }

  public ItemStack item() {
    return item;
  }

  public DisplayEntity item(ItemStack item) {
    this.item = item;
    return this;
  }

  public byte itemDisplayType() {
    return itemDisplayType;
  }

  public DisplayEntity itemDisplayType(byte itemDisplayType) {
    this.itemDisplayType = itemDisplayType;
    return this;
  }

  public int blockState() {
    return blockState;
  }

  public DisplayEntity blockState(int blockState) {
    this.blockState = blockState;
    return this;
  }

  public List<PacketWrapper<?>> spawn() {
    List<PacketWrapper<?>> packets = new ArrayList<>();
    if (isRawEntity()) {
      packets.add(collisionTeamCreate());
    }
    packets.add(new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), entityType,
        location, pitch, yaw, headYaw, 0, Optional.empty()));
    packets.add(dataPacket());
    return packets;
  }

  public WrapperPlayServerDestroyEntities remove() {
    return new WrapperPlayServerDestroyEntities(id);
  }

  public PacketWrapper<?> goTo(Location location) {
    this.location = PacketUtils.vector3d(location.toVector());
    this.pitch = location.getPitch();
    this.yaw = location.getYaw();
    this.headYaw = location.getYaw();
    return new WrapperPlayServerEntityTeleport(id, this.location, yaw, pitch, true);
  }

  public PacketWrapper<?> move(Vector offset) {
    location = location.add(PacketUtils.vector3d(offset));
    return new WrapperPlayServerEntityTeleport(id, location, yaw, pitch, true);
  }

  public PacketWrapper<?> rotate(float yaw, float pitch) {
    this.yaw = yaw;
    this.pitch = pitch;
    this.headYaw = yaw;
    return new WrapperPlayServerEntityTeleport(id, location, yaw, pitch, true);
  }

  public PacketWrapper<?> headLook() {
    return new WrapperPlayServerEntityHeadLook(id, headYaw);
  }

  public boolean isRawEntity() {
    return displayKind == DisplayKind.RAW;
  }

  public WrapperPlayServerTeams collisionTeamRemove() {
    return new WrapperPlayServerTeams(
        collisionTeamName(),
        WrapperPlayServerTeams.TeamMode.REMOVE,
        (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
        List.of()
    );
  }

  public boolean isTextDisplay() {
    return displayKind == DisplayKind.TEXT;
  }

  public boolean isItemDisplay() {
    return displayKind == DisplayKind.ITEM;
  }

  public boolean isBlockDisplay() {
    return displayKind == DisplayKind.BLOCK;
  }

  public PacketWrapper<?> dataPacket() {
    return metadataPacket(ALL_METADATA_INDICES);
  }

  public static WrapperPlayServerEntityMetadata textUpdate(int entityId, Component text) {
    List<EntityData<?>> metadata = new ArrayList<>(1);
    metadata.add(new EntityData<>(MetadataIndex.CONTENT.index(), EntityDataTypes.ADV_COMPONENT, text));
    return new WrapperPlayServerEntityMetadata(entityId, metadata);
  }

  public WrapperPlayServerEntityMetadata metadataPacket(MetadataIndex... indices) {
    List<EntityData<?>> metadata = new ArrayList<>(indices.length);
    for (MetadataIndex index : indices) {
      EntityData<?> data = entityData(index);
      if (data != null) {
        metadata.add(data);
      }
    }
    return new WrapperPlayServerEntityMetadata(id, metadata);
  }

  public static WrapperPlayServerDestroyEntities destroyAll(int[] entityIds) {
    return new WrapperPlayServerDestroyEntities(entityIds);
  }

  private EntityData<?> entityData(MetadataIndex index) {
    if (displayKind == DisplayKind.RAW && !RAW_ENTITY_METADATA_INDICES.contains(index)) {
      return null;
    }
    int wireIndex = index.index();
    return switch (index) {
      case ENTITY_FLAGS -> new EntityData<>(wireIndex, EntityDataTypes.BYTE, entityFlags);
      case NO_GRAVITY -> new EntityData<>(wireIndex, EntityDataTypes.BOOLEAN, noGravity);
      case INTERPOLATION_DELAY -> new EntityData<>(wireIndex, EntityDataTypes.INT, interpolationDelay);
      case INTERPOLATION_DURATION -> new EntityData<>(wireIndex, EntityDataTypes.INT, interpolationDuration);
      case TELEPORT_DURATION -> new EntityData<>(wireIndex, EntityDataTypes.INT, teleportDuration);
      case TRANSLATION -> new EntityData<>(wireIndex, EntityDataTypes.VECTOR3F, translation);
      case SCALE -> new EntityData<>(wireIndex, EntityDataTypes.VECTOR3F, scale);
      case LEFT_ROTATION -> new EntityData<>(wireIndex, EntityDataTypes.QUATERNION, leftRotation);
      case RIGHT_ROTATION -> new EntityData<>(wireIndex, EntityDataTypes.QUATERNION, rightRotation);
      case BILLBOARD -> new EntityData<>(wireIndex, EntityDataTypes.BYTE, billboard);
      case BRIGHTNESS -> new EntityData<>(wireIndex, EntityDataTypes.INT, brightness);
      case VIEW_RANGE -> new EntityData<>(wireIndex, EntityDataTypes.FLOAT, viewRange);
      case SHADOW_RADIUS -> new EntityData<>(wireIndex, EntityDataTypes.FLOAT, shadowRadius);
      case SHADOW_STRENGTH -> new EntityData<>(wireIndex, EntityDataTypes.FLOAT, shadowStrength);
      case WIDTH -> new EntityData<>(wireIndex, EntityDataTypes.FLOAT, width);
      case HEIGHT -> new EntityData<>(wireIndex, EntityDataTypes.FLOAT, height);
      case GLOW_COLOR_OVERRIDE -> new EntityData<>(wireIndex, EntityDataTypes.INT, glowColorOverride);
      case CONTENT -> contentData(wireIndex);
      case CONTENT_STYLE -> switch (displayKind) {
        case TEXT -> new EntityData<>(wireIndex, EntityDataTypes.INT, lineWidth);
        case ITEM -> new EntityData<>(wireIndex, EntityDataTypes.BYTE, itemDisplayType);
        case BLOCK, RAW -> null;
      };
      case TEXT_BACKGROUND -> displayKind == DisplayKind.TEXT
          ? new EntityData<>(wireIndex, EntityDataTypes.INT, backgroundColor)
          : null;
      case TEXT_OPACITY -> displayKind == DisplayKind.TEXT
          ? new EntityData<>(wireIndex, EntityDataTypes.BYTE, textOpacity)
          : null;
      case TEXT_FLAGS -> displayKind == DisplayKind.TEXT
          ? new EntityData<>(wireIndex, EntityDataTypes.BYTE, textFlags)
          : null;
    };
  }

  private EntityData<?> contentData(int wireIndex) {
    return switch (displayKind) {
      case TEXT -> new EntityData<>(wireIndex, EntityDataTypes.ADV_COMPONENT, text);
      case ITEM -> new EntityData<>(wireIndex, EntityDataTypes.ITEMSTACK, fromBukkitItemStack(item));
      case BLOCK -> new EntityData<>(wireIndex, EntityDataTypes.BLOCK_STATE, blockState);
      case RAW -> null;
    };
  }

  private WrapperPlayServerTeams collisionTeamCreate() {
    return new WrapperPlayServerTeams(
        collisionTeamName(),
        WrapperPlayServerTeams.TeamMode.CREATE,
        COLLISIONLESS_TEAM,
        uuid.toString()
    );
  }

  private String collisionTeamName() {
    return COLLISION_TEAM_PREFIX + Integer.toUnsignedString(id, 36);
  }

  public static final class Builder {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(Integer.MIN_VALUE);

    private final DisplayEntity displayEntity;

    private Builder(EntityType type, DisplayKind displayKind) {
      this.displayEntity = new DisplayEntity(nextId(), UUID.randomUUID(), type)
          .displayKind(displayKind);
    }

    public static DisplayEntity textDisplay(Component component, Location loc) {
      return textDisplay(component, loc, 1.00F);
    }

    public static DisplayEntity textDisplay(Component component, Location loc, float scale) {
      return textDisplay(component, loc, scale, (byte) 0, (byte) 0, 0);
    }

    public static DisplayEntity textDisplay(Component component, Location loc, float scale, byte billboard, byte textFlags, int backgroundColor) {
      return textDisplay(component, loc, scale, scale, scale, billboard, textFlags, backgroundColor, (byte) 0xFF);
    }

    public static DisplayEntity textDisplay(Component component, Location loc, float scaleX, float scaleY, float scaleZ, byte billboard, byte textFlags, int backgroundColor, byte textOpacity) {
      return new Builder(EntityTypes.TEXT_DISPLAY, DisplayKind.TEXT)
          .text(component)
          .noGravity(true)
          .billboard(billboard)
          .shadow(0f, 0f)
          .textOpacity(textOpacity)
          .lineWidth(TextDisplayLayout.FULL_WIDTH)
          .backgroundColor(backgroundColor)
          .textFlags(textFlags)
          .scale(scaleX, scaleY, scaleZ)
          .pos(loc)
          .build();
    }

    public static DisplayEntity itemDisplay(ItemStack stack, Location loc) {
      return itemDisplay(stack, loc, 1.00F);
    }

    public static DisplayEntity itemDisplay(ItemStack stack, Location loc, float scale) {
      return itemDisplay(stack, loc, scale, (byte) 0, (byte) 0);
    }

    public static DisplayEntity itemDisplay(ItemStack stack, Location loc, float scale, byte billboard, byte itemDisplayType) {
      return new Builder(EntityTypes.ITEM_DISPLAY, DisplayKind.ITEM)
          .item(stack)
          .noGravity(true)
          .billboard(billboard)
          .shadow(0f, 0f)
          .itemDisplayType(itemDisplayType)
          .scale(scale, scale, scale)
          .pos(loc)
          .build();
    }

    public static DisplayEntity blockDisplay(BlockData blockData, Location loc) {
      return new Builder(EntityTypes.BLOCK_DISPLAY, DisplayKind.BLOCK)
          .blockState(fromBukkitBlockData(blockData).getGlobalId())
          .noGravity(true)
          .shadow(0f, 0f)
          .pos(loc)
          .build();
    }

    public static DisplayEntity entity(EntityType entityType, Location loc) {
      return new Builder(entityType, DisplayKind.RAW)
          .noGravity(true)
          .pos(loc)
          .build();
    }

    private static int nextId() {
      return NEXT_ID.getAndUpdate(i -> {
        if (++i < 0) return i;
        Gloss.log(Level.SEVERE, "Entity IDs overflow");
        Gloss.log(Level.SEVERE, "Please restart your server!");
        return Integer.MIN_VALUE;
      });
    }

    public Builder pos(Location loc) {
      displayEntity.location(PacketUtils.vector3d(loc.toVector()))
          .yaw(loc.getYaw())
          .pitch(loc.getPitch())
          .headYaw(loc.getYaw());
      return this;
    }

    public Builder rot(float pitch, float yaw) {
      displayEntity.pitch(pitch);
      displayEntity.yaw(yaw);
      return this;
    }

    public Builder noGravity(boolean noGravity) {
      displayEntity.noGravity(noGravity);
      return this;
    }

    public Builder billboard(byte billboard) {
      displayEntity.billboard(billboard);
      return this;
    }

    public Builder shadow(float radius, float strength) {
      displayEntity.shadowRadius(radius);
      displayEntity.shadowStrength(strength);
      return this;
    }

    public Builder text(Component component) {
      displayEntity.text(component == null ? Component.empty() : component);
      return this;
    }

    public Builder lineWidth(int width) {
      displayEntity.lineWidth(width);
      return this;
    }

    public Builder backgroundColor(int color) {
      displayEntity.backgroundColor(color);
      return this;
    }

    public Builder textOpacity(byte opacity) {
      displayEntity.textOpacity(opacity);
      return this;
    }

    public Builder textFlags(byte flags) {
      displayEntity.textFlags(flags);
      return this;
    }

    public Builder item(ItemStack stack) {
      displayEntity.item(stack == null ? new ItemStack(Material.AIR) : stack.clone());
      return this;
    }

    public Builder itemDisplayType(byte type) {
      displayEntity.itemDisplayType(type);
      return this;
    }

    public Builder blockState(int state) {
      displayEntity.blockState(state);
      return this;
    }

    public Builder scale(float x, float y, float z) {
      displayEntity.scale(new Vector3f(x, y, z));
      return this;
    }

    public DisplayEntity build() {
      return displayEntity;
    }
  }

  enum DisplayKind {
    RAW,
    TEXT,
    ITEM,
    BLOCK
  }

  /**
   * Entity metadata slots in wire order. {@code CONTENT} and {@code CONTENT_STYLE} mean different
   * things per display kind — text component, item stack or block state, then line width or item
   * display type. These numbers are the client's protocol contract: never renumber them.
   */
  public enum MetadataIndex {
    ENTITY_FLAGS(0),
    NO_GRAVITY(5),
    INTERPOLATION_DELAY(8),
    INTERPOLATION_DURATION(9),
    TELEPORT_DURATION(10),
    TRANSLATION(11),
    SCALE(12),
    LEFT_ROTATION(13),
    RIGHT_ROTATION(14),
    BILLBOARD(15),
    BRIGHTNESS(16),
    VIEW_RANGE(17),
    SHADOW_RADIUS(18),
    SHADOW_STRENGTH(19),
    WIDTH(20),
    HEIGHT(21),
    GLOW_COLOR_OVERRIDE(22),
    CONTENT(23),
    CONTENT_STYLE(24),
    TEXT_BACKGROUND(25),
    TEXT_OPACITY(26),
    TEXT_FLAGS(27);

    private final int index;

    MetadataIndex(int index) {
      this.index = index;
    }

    public int index() {
      return index;
    }
  }
}
