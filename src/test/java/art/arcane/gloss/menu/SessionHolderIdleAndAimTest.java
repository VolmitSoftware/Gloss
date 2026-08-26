package art.arcane.gloss.menu;

import art.arcane.gloss.preview.ContainerPreview;
import art.arcane.gloss.preview.PreviewElement;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pins the two new pieces of holder state Lane C added: idle pruning (T16/C2) and the preview aim
 * record that lets an unmoved viewer skip the look-at ray traces (C1).
 *
 * <p>{@code CharacterizationSessionHolderLifecycleTest} owns the lifecycle outcomes; this suite
 * only covers what those pins deliberately do not constrain.
 */
public class SessionHolderIdleAndAimTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000a1de0");

  @Before
  public void resetTelemetry() {
    GlossTelemetry.clear();
  }

  @After
  public void clearTelemetry() {
    GlossTelemetry.clear();
  }

  // ---------------------------------------------------------------------
  // Idle pruning
  // ---------------------------------------------------------------------

  @Test
  public void aHolderCarryingNothingIsIdleDisposableAndRemovableOnTick() {
    SessionHolder holder = new SessionHolder(player(new AtomicBoolean(true), new AtomicReference<>(eye(0.0F))),
        new PlayerSnapshotStore<>());

    assertTrue(holder.isIdle());
    assertTrue(holder.isDisposable());
    assertTrue("an empty holder must report itself removable so the sweep can prune it",
        holder.tick());
  }

  @Test
  public void aHolderWithAnOpenPreviewIsNeitherIdleNorDisposable() throws ReflectiveOperationException {
    Player viewer = player(new AtomicBoolean(true), new AtomicReference<>(eye(0.0F)));
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    holder.openPreview(emptyPreview(viewer));

    assertFalse(holder.isIdle());
    assertFalse(holder.isDisposable());
    assertFalse("a holder with live work must survive the sweep", holder.tick());
  }

  @Test
  public void anOfflineHolderIsDisposableEvenWithWork() throws ReflectiveOperationException {
    AtomicBoolean online = new AtomicBoolean(true);
    Player viewer = player(online, new AtomicReference<>(eye(0.0F)));
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    holder.openPreview(emptyPreview(viewer));
    online.set(false);

    assertTrue(holder.isDisposable());
  }

  // ---------------------------------------------------------------------
  // Preview aim record
  // ---------------------------------------------------------------------

  @Test
  public void withNoPreviewOpenTheAimRecordNeverStabilises() {
    Location pose = eye(0.0F);
    SessionHolder holder = new SessionHolder(player(new AtomicBoolean(true), new AtomicReference<>(pose)),
        new PlayerSnapshotStore<>());
    Object target = new Object();

    holder.recordAim(pose, target);

    assertNull("without an open preview there is nothing to keep, so a scan must always run",
        holder.stableAim(pose));
  }

  @Test
  public void anUnmovedEyeWithAnOpenPreviewKeepsTheRecordedTarget() throws ReflectiveOperationException {
    Location pose = eye(12.5F);
    Player viewer = player(new AtomicBoolean(true), new AtomicReference<>(pose));
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    Object target = new Object();
    holder.recordAim(pose, target);
    holder.openPreview(emptyPreview(viewer));

    assertSame(target, holder.stableAim(eye(12.5F)));
  }

  @Test
  public void anyChangeToTheEyePoseDropsTheRecord() throws ReflectiveOperationException {
    Location pose = eye(12.5F);
    Player viewer = player(new AtomicBoolean(true), new AtomicReference<>(pose));
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    holder.recordAim(pose, new Object());
    holder.openPreview(emptyPreview(viewer));

    assertNull("a different yaw is a different ray", holder.stableAim(eye(12.6F)));

    holder.recordAim(pose, new Object());
    Location moved = eye(12.5F);
    moved.setX(moved.getX() + 0.001D);
    assertNull("a different position is a different ray", holder.stableAim(moved));
  }

  @Test
  public void closingThePreviewForgetsTheRecord() throws ReflectiveOperationException {
    Location pose = eye(12.5F);
    Player viewer = player(new AtomicBoolean(true), new AtomicReference<>(pose));
    SessionHolder holder = new SessionHolder(viewer, new PlayerSnapshotStore<>());
    Object target = new Object();
    holder.recordAim(pose, target);
    holder.openPreview(emptyPreview(viewer));
    assertSame(target, holder.stableAim(eye(12.5F)));

    holder.closePreview();

    assertNull(holder.stableAim(eye(12.5F)));
  }

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

  private static Location eye(float yaw) {
    return new Location(null, 4.0D, 66.62D, -2.0D, yaw, 5.0F);
  }

  private static ContainerPreview emptyPreview(Player viewer) throws ReflectiveOperationException {
    Constructor<ContainerPreview> constructor = ContainerPreview.class.getDeclaredConstructor(
        Player.class, Block.class, Entity.class, Vector.class, List.class, List.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(viewer, null, null, new Vector(0.5D, 65.5D, 0.5D),
        List.<PreviewElement>of(), List.of(), false);
  }

  private static Player player(AtomicBoolean online, AtomicReference<Location> eye) {
    ItemStack mainHand = art.arcane.gloss.preview.doc.PreviewFakes.stack(Material.STICK, 1);
    PlayerInventory inventory = (PlayerInventory) CharacterizationSupport.proxy(
        new Class<?>[]{PlayerInventory.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getItemInMainHand" -> mainHand;
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> PLAYER;
          case "isOnline" -> online.get();
          case "hasPermission" -> true;
          case "getGameMode" -> GameMode.SURVIVAL;
          case "getInventory" -> inventory;
          case "getEyeLocation" -> eye.get().clone();
          case "getLocation" -> eye.get().clone();
          case "getName" -> "idler";
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }
}
