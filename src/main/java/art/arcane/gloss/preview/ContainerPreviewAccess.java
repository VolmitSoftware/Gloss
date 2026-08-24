package art.arcane.gloss.preview;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.integration.protection.ContainerProtectionService;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.EnderChest;
import org.bukkit.block.Lockable;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permissible;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ContainerPreviewAccess {
  public static final String PERMISSION = "gloss.preview";

  private static final double COLLISION_EPSILON = 1.0E-6D;
  private static final String BLOCK_LOCK_CHECK_EVENT = "io.papermc.paper.event.block.BlockLockCheckEvent";
  private static final String BLOCK_LOCK_LISTENER = "art.arcane.gloss.paper.PaperBlockLockListener";
  private static final AtomicBoolean LOCK_FAILURE_LOGGED = new AtomicBoolean();

  private static final Method CHEST_IS_BLOCKED = paperStateMethod(Chest.class, "isBlocked");
  private static final Method ENDER_CHEST_IS_BLOCKED = paperStateMethod(EnderChest.class, "isBlocked");
  private static final Method SHULKER_BOX_IS_OPEN = paperStateMethod(ShulkerBox.class, "isOpen");

  private static volatile LockReflection lockReflection;
  private static volatile LockCheck lockCheck;
  private static volatile boolean lockCheckResolved;

  private ContainerPreviewAccess() {
  }

  public static boolean canView(Permissible viewer) {
    return canView(viewer, GlossConfig.current().previews().enabled());
  }

  public static boolean isEnabled() {
    return GlossConfig.current().previews().enabled();
  }

  public static ViewerAccess capture(Player viewer) {
    boolean previewPermitted = canView(viewer);
    boolean spectator = viewer.getGameMode() == GameMode.SPECTATOR;
    ItemStack mainHandItem = viewer.getInventory().getItemInMainHand().clone();
    return new ViewerAccess(previewPermitted, spectator, mainHandItem);
  }

  public static boolean canOpen(Player viewer, Block block, ViewerAccess access) {
    if (!access.previewPermitted()) {
      return false;
    }
    BlockState state = block.getState();
    if (!isPhysicallyOpenable(block, state) || !canUnlock(viewer, block, state, access)) {
      return false;
    }
    Block connected = connectedChest(block);
    if (connected != null) {
      BlockState connectedState = connected.getState();
      if (!isPhysicallyOpenable(connected, connectedState)
          || !canUnlock(viewer, connected, connectedState, access)) {
        return false;
      }
    }
    Gloss instance = Gloss.instance;
    ContainerProtectionService protection = instance == null ? null : instance.getContainerProtection();
    return protection == null || protection.canAccess(viewer, block);
  }

  public static boolean canOpen(Player viewer, Entity entity, ViewerAccess access) {
    if (!access.previewPermitted()) {
      return false;
    }
    Gloss instance = Gloss.instance;
    ContainerProtectionService protection = instance == null ? null : instance.getContainerProtection();
    return protection == null || protection.canAccess(viewer, entity);
  }

  public static boolean isOpenable(Block block) {
    BlockState state = block.getState();
    if (!isOpenable(state)) {
      return false;
    }
    return isPhysicallyOpenable(block, state);
  }

  private static boolean isPhysicallyOpenable(Block block, BlockState state) {
    if (!isPhysicallyOpenable(state)) {
      return false;
    }
    if (!(state instanceof ShulkerBox shulkerBox)) {
      return true;
    }
    if (stateFlag(SHULKER_BOX_IS_OPEN, shulkerBox) || !shulkerBox.getInventory().getViewers().isEmpty()) {
      return true;
    }
    if (!(block.getBlockData() instanceof Directional directional)) {
      return false;
    }
    BoundingBox lidBounds = shulkerLidBounds(block.getX(), block.getY(), block.getZ(), directional.getFacing());
    if (block.getRelative(directional.getFacing()).getCollisionShape().overlaps(lidBounds)) {
      return false;
    }
    for (Entity entity : block.getWorld().getNearbyEntities(lidBounds)) {
      if (entity.isValid() && entity.getBoundingBox().overlaps(lidBounds)) {
        return false;
      }
    }
    return true;
  }

  static boolean canView(Permissible viewer, boolean enabled) {
    return enabled && viewer.hasPermission(PERMISSION);
  }

  static boolean isOpenable(BlockState state) {
    if (state instanceof Lockable lockable && lockable.isLocked()) {
      return false;
    }
    return isPhysicallyOpenable(state);
  }

  static boolean isPhysicallyOpenable(BlockState state) {
    if (state instanceof Chest chest) {
      return !stateFlag(CHEST_IS_BLOCKED, chest);
    }
    if (state instanceof EnderChest enderChest) {
      return !stateFlag(ENDER_CHEST_IS_BLOCKED, enderChest);
    }
    return true;
  }

  private static Method paperStateMethod(Class<?> type, String name) {
    try {
      return type.getMethod(name);
    } catch (NoSuchMethodException absent) {
      return null;
    }
  }

  private static boolean stateFlag(Method method, BlockState state) {
    if (method == null) {
      return false;
    }
    try {
      return (boolean) method.invoke(state);
    } catch (ReflectiveOperationException | RuntimeException failure) {
      return false;
    }
  }

  private static boolean canUnlock(Player viewer, Block block, BlockState state, ViewerAccess access) {
    if (!(state instanceof Lockable lockable) || !lockable.isLocked()) {
      return true;
    }
    LockCheck check = lockCheck();
    if (check == null) {
      return access.spectator() || matchesLock(state, access.mainHandItem());
    }
    LockCheckDecision decision = check.check(block, viewer, access.mainHandItem().clone());
    if (decision.result() == Event.Result.ALLOW) {
      return true;
    }
    if (decision.result() == Event.Result.DENY || !access.spectator()) {
      ItemStack keyItem = decision.customKeyItem() != null
          ? decision.customKeyItem()
          : access.mainHandItem();
      return decision.result() != Event.Result.DENY && matchesLock(state, keyItem);
    }
    return true;
  }

  private static LockCheck lockCheck() {
    if (lockCheckResolved) {
      return lockCheck;
    }
    LockCheck resolved = null;
    try {
      Class.forName(BLOCK_LOCK_CHECK_EVENT);
      Class<?> type = Class.forName(BLOCK_LOCK_LISTENER);
      resolved = (LockCheck) type.getConstructor().newInstance();
    } catch (ClassNotFoundException absent) {
      resolved = null;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      logLockFailure(failure);
      resolved = null;
    }
    lockCheck = resolved;
    lockCheckResolved = true;
    return resolved;
  }

  private static boolean matchesLock(BlockState state, ItemStack keyItem) {
    try {
      LockReflection reflection = lockReflection;
      if (reflection == null) {
        reflection = resolveLockReflection(state);
        lockReflection = reflection;
      }
      Object blockEntity = reflection.getBlockEntity().invoke(state);
      Object lockCode = reflection.lockKey().get(blockEntity);
      Object nmsItem = reflection.asNmsCopy().invoke(null, keyItem);
      return (boolean) reflection.unlocksWith().invoke(lockCode, nmsItem);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
      logLockFailure(ex);
      return false;
    }
  }

  private static LockReflection resolveLockReflection(BlockState state) throws ReflectiveOperationException {
    Method getBlockEntity = state.getClass().getMethod("getBlockEntity");
    Object blockEntity = getBlockEntity.invoke(state);
    Field lockKey = blockEntity.getClass().getField("lockKey");
    Object lockCode = lockKey.get(blockEntity);
    ClassLoader classLoader = state.getClass().getClassLoader();
    Class<?> craftItemStackClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", true, classLoader);
    Method asNmsCopy = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
    Method unlocksWith = lockCode.getClass().getMethod("unlocksWith", asNmsCopy.getReturnType());
    return new LockReflection(getBlockEntity, lockKey, asNmsCopy, unlocksWith);
  }

  private static void logLockFailure(Throwable throwable) {
    if (!LOCK_FAILURE_LOGGED.compareAndSet(false, true)) {
      return;
    }
    Gloss.logExceptionStack(true, throwable,
        "Container lock access could not be evaluated. Locked container previews will remain hidden.");
  }

  private static Block connectedChest(Block block) {
    if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chest)
        || chest.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) {
      return null;
    }
    BlockFace connectedFace = chest.getType() == org.bukkit.block.data.type.Chest.Type.LEFT
        ? clockwise(chest.getFacing())
        : counterClockwise(chest.getFacing());
    Block connected = block.getRelative(connectedFace);
    if (!(connected.getBlockData() instanceof org.bukkit.block.data.type.Chest connectedData)
        || connectedData.getFacing() != chest.getFacing()
        || connectedData.getType() == chest.getType()) {
      return null;
    }
    return connected;
  }

  private static BlockFace clockwise(BlockFace face) {
    return switch (face) {
      case NORTH -> BlockFace.EAST;
      case EAST -> BlockFace.SOUTH;
      case SOUTH -> BlockFace.WEST;
      case WEST -> BlockFace.NORTH;
      default -> throw new IllegalArgumentException("Unsupported chest facing: " + face);
    };
  }

  private static BlockFace counterClockwise(BlockFace face) {
    return switch (face) {
      case NORTH -> BlockFace.WEST;
      case WEST -> BlockFace.SOUTH;
      case SOUTH -> BlockFace.EAST;
      case EAST -> BlockFace.NORTH;
      default -> throw new IllegalArgumentException("Unsupported chest facing: " + face);
    };
  }

  static BoundingBox shulkerLidBounds(int x, int y, int z, BlockFace facing) {
    double minX = x + COLLISION_EPSILON;
    double minY = y + COLLISION_EPSILON;
    double minZ = z + COLLISION_EPSILON;
    double maxX = x + 1.0D - COLLISION_EPSILON;
    double maxY = y + 1.0D - COLLISION_EPSILON;
    double maxZ = z + 1.0D - COLLISION_EPSILON;
    return switch (facing) {
      case DOWN -> new BoundingBox(minX, y - 0.5D + COLLISION_EPSILON, minZ, maxX, y - COLLISION_EPSILON, maxZ);
      case UP -> new BoundingBox(minX, y + 1.0D + COLLISION_EPSILON, minZ, maxX, y + 1.5D - COLLISION_EPSILON, maxZ);
      case NORTH -> new BoundingBox(minX, minY, z - 0.5D + COLLISION_EPSILON, maxX, maxY, z - COLLISION_EPSILON);
      case SOUTH -> new BoundingBox(minX, minY, z + 1.0D + COLLISION_EPSILON, maxX, maxY, z + 1.5D - COLLISION_EPSILON);
      case WEST -> new BoundingBox(x - 0.5D + COLLISION_EPSILON, minY, minZ, x - COLLISION_EPSILON, maxY, maxZ);
      case EAST -> new BoundingBox(x + 1.0D + COLLISION_EPSILON, minY, minZ, x + 1.5D - COLLISION_EPSILON, maxY, maxZ);
      default -> throw new IllegalArgumentException("Unsupported shulker facing: " + facing);
    };
  }

  public record ViewerAccess(boolean previewPermitted, boolean spectator, ItemStack mainHandItem) {
    public ViewerAccess {
      mainHandItem = mainHandItem == null
          ? new ItemStack(Material.AIR)
          : mainHandItem.clone();
    }
  }

  public interface LockCheck {
    LockCheckDecision check(Block block, Player viewer, ItemStack keyItem);
  }

  public record LockCheckDecision(Event.Result result, ItemStack customKeyItem) {
  }

  private record LockReflection(Method getBlockEntity, Field lockKey, Method asNmsCopy, Method unlocksWith) {
  }
}
